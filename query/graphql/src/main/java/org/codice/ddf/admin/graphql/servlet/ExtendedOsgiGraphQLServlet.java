/**
 * Copyright (c) Codice Foundation
 *
 * <p>This is free software: you can redistribute it and/or modify it under the terms of the GNU
 * Lesser General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or any later version.
 *
 * <p>This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details. A copy of the GNU Lesser General Public
 * License is distributed along with this program and can be found at
 * <http://www.gnu.org/licenses/lgpl.html>.
 */
package org.codice.ddf.admin.graphql.servlet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.RemovalCause;
import com.google.common.cache.RemovalNotification;
import com.google.common.net.HttpHeaders;
import graphql.GraphQLError;
import graphql.execution.AsyncExecutionStrategy;
import graphql.execution.DataFetcherExceptionHandlerParameters;
import graphql.execution.ExecutionPath;
import graphql.execution.ExecutionStrategy;
import graphql.execution.SimpleDataFetcherExceptionHandler;
import graphql.schema.GraphQLFieldDefinition;
import graphql.servlet.DefaultGraphQLErrorHandler;
import graphql.servlet.ExecutionStrategyProvider;
import graphql.servlet.GraphQLErrorHandler;
import graphql.servlet.GraphQLMutationProvider;
import graphql.servlet.GraphQLProvider;
import graphql.servlet.GraphQLQueryProvider;
import graphql.servlet.OsgiGraphQLServlet;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.codice.ddf.admin.api.Events;
import org.codice.ddf.admin.api.FieldProvider;
import org.codice.ddf.admin.api.report.ErrorMessage;
import org.codice.ddf.admin.graphql.servlet.request.DelegateRequest;
import org.codice.ddf.admin.graphql.servlet.request.DelegateResponse;
import org.codice.ddf.admin.graphql.transform.FunctionDataFetcherException;
import org.codice.ddf.admin.graphql.transform.GraphQLTransformCommons;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SuppressWarnings("squid:S2226" /* private variables need to remain non-static and/or non-final */)
public class ExtendedOsgiGraphQLServlet extends OsgiGraphQLServlet implements EventHandler {

  private static final Logger LOGGER = LoggerFactory.getLogger(ExtendedOsgiGraphQLServlet.class);
  private static final long CACHE_EXPIRATION_IN_SECONDS = 1;
  private static final long CACHE_CLEANUP_INVOCATION_IN_SECONDS = 1;

  private static final String BINDING_FIELD_PROVIDER = "GraphQL servlet binding field provider %s";
  private static final String UNBINDING_FIELD_PROVIDER =
      "GraphQL servlet unbinding field provider %s";

  private Cache<String, Object> cache;
  private ScheduledExecutorService scheduler;
  private List<FieldProvider> fieldProviders;
  private List<GraphQLProviderImpl> transformedProviders;
  private ExecutionStrategyProvider execStrategy;
  private GraphQLErrorHandler errorHandler;
  private GraphQLQueryProvider errorCodeProvider;

  private static final int MAX_REQUEST_SIZE = 1_000_000;

  private static final int MAX_QUERY_SIZE = 10;

  public static final String INVALID_BATCH_SIZE_MSG =
      "Invalid batch request size. The batch request size must be an integer less than or equal to "
          + MAX_QUERY_SIZE;

  public static final String INVALID_CONTENT_LENGTH_MSG =
      "Invalid Content-Length header value. The Content-Length must be an integer less than or equal to "
          + MAX_REQUEST_SIZE
          + " bytes";

  public static final String MISSING_CONTENT_LENGTH_HEADER_MSG =
      "Content-Length header is required.";

  public ExtendedOsgiGraphQLServlet() {
    super();
    cache =
        CacheBuilder.newBuilder()
            .expireAfterWrite(CACHE_EXPIRATION_IN_SECONDS, TimeUnit.SECONDS)
            .removalListener(this::refreshSchemaOnExpire)
            .build();

    scheduler = Executors.newScheduledThreadPool(1);
    scheduler.scheduleAtFixedRate(
        () -> cache.cleanUp(),
        CACHE_CLEANUP_INVOCATION_IN_SECONDS,
        CACHE_CLEANUP_INVOCATION_IN_SECONDS,
        TimeUnit.SECONDS);

    fieldProviders = new ArrayList<>();
    transformedProviders = new ArrayList<>();
    execStrategy = new ExecutionStrategyProviderImpl();
    errorHandler = new GraphQLErrorHandlerImpl();
  }

  @Override
  public void destroy() {
    scheduler.shutdownNow();
  }

  @Override
  public void handleEvent(Event event) {
    if (Events.REFRESH_SCHEMA.equals(event.getTopic())) {
      triggerSchemaRefresh((String) event.getProperty(Events.EVENT_REASON));
    }
  }

  @Override
  protected ExecutionStrategyProvider getExecutionStrategyProvider() {
    return execStrategy;
  }

  @Override
  protected GraphQLErrorHandler getGraphQLErrorHandler() {
    return errorHandler;
  }

  @Override
  @SuppressWarnings({
    "squid:S1181" /* Catching throwable intentionally */,
    "squid:S1989" /* Throwing exception in servlet intentionally */
  })
  protected void doPost(HttpServletRequest originalRequest, HttpServletResponse originalResponse)
      throws ServletException, IOException {

    if (StringUtils.isEmpty(originalRequest.getHeader(HttpHeaders.CONTENT_LENGTH))) {
      originalResponse.getWriter().write(MISSING_CONTENT_LENGTH_HEADER_MSG);
      originalResponse.setStatus(411);
      return;
    }

    try {
      int contentLength = Integer.parseInt(originalRequest.getHeader(HttpHeaders.CONTENT_LENGTH));
      if (contentLength > MAX_REQUEST_SIZE) {
        originalResponse.getWriter().write(INVALID_CONTENT_LENGTH_MSG);
        originalResponse.setStatus(413);
        return;
      }
    } catch (Exception e) {
      originalResponse.getWriter().write(INVALID_CONTENT_LENGTH_MSG);
      originalResponse.setStatus(400);
      return;
    }

    // TODO: tbatie - 6/9/17 - GraphQLServlet does not support batched requests even though a
    // BatchedExecutionStrategy exists. This should be fixed in the GraphQLServlet and contributed
    // back to graphql-java-servlet
    List<String> responses = new ArrayList<>();
    String originalReqContent = IOUtils.toString(originalRequest.getInputStream());
    boolean isBatchRequest = isBatchRequest(originalReqContent);

    try {
      List<String> splitReqs = splitQueries(originalReqContent);

      if (splitReqs.size() > MAX_QUERY_SIZE) {
        originalResponse.getWriter().write(INVALID_BATCH_SIZE_MSG);
        originalResponse.setStatus(429);
        return;
      }

      for (String reqStr : splitReqs) {
        DelegateResponse response = new DelegateResponse(originalResponse);
        super.doPost(new DelegateRequest(originalRequest, reqStr), response);
        responses.add(response.getDelegatedResponse());
      }

      originalResponse.setContentType(APPLICATION_JSON_UTF8);
      originalResponse.setStatus(STATUS_OK);
      originalResponse
          .getWriter()
          .write(isBatchRequest ? "[" + String.join(",", responses) + "]" : responses.get(0));
    } catch (RuntimeException t) {
      originalResponse.setStatus(500);
    } catch (Throwable t) {
      originalResponse.setStatus(400);
      log.trace("Error executing GraphQL request!", t);
    }
  }

  private List<String> splitQueries(String requestContent) throws IOException {
    List<String> splitElements = new ArrayList<>();
    JsonNode jsonNode = new ObjectMapper().readTree(requestContent);
    if (jsonNode.isArray()) {
      jsonNode.elements().forEachRemaining(node -> splitElements.add(node.toString()));
    } else {
      splitElements.add(jsonNode.toString());
    }
    return splitElements;
  }

  private boolean isBatchRequest(String requestContent) throws IOException {
    return new ObjectMapper().readTree(requestContent).isArray();
  }

  private void triggerSchemaRefresh(String refreshReason) {
    LOGGER.trace("GraphQL schema refresh requested. Cause: {}", refreshReason);
    cache.put(Events.REFRESH_SCHEMA, true);
  }

  /**
   * Refreshes the schema periodically once the cache invalidates if a REFRESH_SCHEMA event was
   * added to the cache. This allows multiple threads to ask for a schema refresh while only
   * refreshing the schema once.
   *
   * @param notification
   */
  private void refreshSchemaOnExpire(RemovalNotification notification) {
    if (notification.getCause() == RemovalCause.EXPIRED) {
      refreshSchema();
    }
  }

  // Synchronized just in case the schema is still updating when another refresh is called
  // The performance decrease by the `synchronized` is negligible because of the periodic cache
  // invalidation implementation
  private synchronized void refreshSchema() {
    LOGGER.trace("Refreshing GraphQL schema.");

    transformedProviders.forEach(this::unbindProvider);

    if (errorCodeProvider != null) {
      unbindProvider(errorCodeProvider);
    }

    GraphQLTransformCommons transformer = new GraphQLTransformCommons();

    transformedProviders =
        fieldProviders
            .stream()
            .map(fieldProvider -> new GraphQLProviderImpl(fieldProvider, transformer))
            .collect(Collectors.toList());

    errorCodeProvider = transformer.getErrorCodesQueryProvider(fieldProviders);

    transformedProviders.forEach(this::bindProvider);

    if (errorCodeProvider != null) {
      bindProvider(errorCodeProvider);
    }

    LOGGER.trace("Finished refreshing GraphQL schema.");
  }

  public void bindFieldProvider(FieldProvider fieldProvider) {
    triggerSchemaRefresh(String.format(BINDING_FIELD_PROVIDER, fieldProvider.getFieldType()));
  }

  public void unbindFieldProvider(FieldProvider fieldProvider) {
    triggerSchemaRefresh(
        String.format(
            UNBINDING_FIELD_PROVIDER, fieldProvider == null ? "" : fieldProvider.getFieldType()));
  }

  public void setFieldProviders(List<FieldProvider> fieldProviders) {
    this.fieldProviders = fieldProviders;
  }

  private static class GraphQLProviderImpl
      implements GraphQLProvider, GraphQLQueryProvider, GraphQLMutationProvider {

    private List<GraphQLFieldDefinition> queries;

    private List<GraphQLFieldDefinition> mutations;

    public GraphQLProviderImpl(FieldProvider provider, GraphQLTransformCommons transformCommons) {
      queries = transformCommons.fieldProviderToQueries(provider);
      mutations = transformCommons.fieldProviderToMutations(provider);
    }

    @Override
    public Collection<GraphQLFieldDefinition> getMutations() {
      return mutations;
    }

    @Override
    public Collection<GraphQLFieldDefinition> getQueries() {
      return queries;
    }
  }

  private static class DataFetcherExceptionHandlerImpl extends SimpleDataFetcherExceptionHandler {

    @Override
    public void accept(
        DataFetcherExceptionHandlerParameters dataFetcherExceptionHandlerParameters) {
      Throwable e = dataFetcherExceptionHandlerParameters.getException();

      if (e instanceof FunctionDataFetcherException) {
        for (ErrorMessage msg : ((FunctionDataFetcherException) e).getCustomMessages()) {
          LOGGER.trace("Unsuccessful GraphQL request:\n", e);
          ExecutionPath executionPath = listToExecutionPath(msg.getPath());
          dataFetcherExceptionHandlerParameters
              .getExecutionContext()
              .addError(new DataFetchingGraphQLError(msg, executionPath), executionPath);
        }
      } else {
        LOGGER.debug("Internal error.", e);
        super.accept(dataFetcherExceptionHandlerParameters);
      }
    }
  }

  public static ExecutionPath listToExecutionPath(List<Object> path) {
    ExecutionPath transformedPath = ExecutionPath.rootPath();
    for (Object seg : path) {
      if (seg instanceof String) {
        transformedPath = transformedPath.segment((String) seg);
      } else {
        transformedPath = transformedPath.segment((Integer) seg);
      }
    }
    return transformedPath;
  }

  private static class ExtendedEnhancedExecutionStrategy extends AsyncExecutionStrategy {

    public ExtendedEnhancedExecutionStrategy() {
      super(new DataFetcherExceptionHandlerImpl());
    }
  }

  private static class ExecutionStrategyProviderImpl implements ExecutionStrategyProvider {

    private ExtendedEnhancedExecutionStrategy strategy;

    public ExecutionStrategyProviderImpl() {
      strategy = new ExtendedEnhancedExecutionStrategy();
    }

    @Override
    public ExecutionStrategy getQueryExecutionStrategy() {
      return strategy;
    }

    @Override
    public ExecutionStrategy getMutationExecutionStrategy() {
      return strategy;
    }

    @Override
    public ExecutionStrategy getSubscriptionExecutionStrategy() {
      return strategy;
    }
  }

  public static class GraphQLErrorHandlerImpl extends DefaultGraphQLErrorHandler {

    @Override
    protected boolean isClientError(GraphQLError error) {
      return error instanceof DataFetchingGraphQLError || super.isClientError(error);
    }
  }
}
