package org.codice.ddf.graphql;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.codice.ddf.admin.api.Events;
import org.codice.ddf.admin.api.FieldProvider;
import org.codice.ddf.graphql.transform.GraphQLTransformCommons;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.RemovalCause;
import com.google.common.cache.RemovalNotification;

import graphql.servlet.GraphQLProvider;

public class GraphQLTransformer implements EventHandler {

  private static final Logger LOGGER = LoggerFactory.getLogger(GraphQLTransformer.class);

  private static final long CACHE_EXPIRATION_IN_SECONDS = 1;
  private static final long CACHE_CLEANUP_INVOCATION_IN_SECONDS = 1;

  private static final String BINDING_FIELD_PROVIDER = "GraphQL servlet binding field provider %s";
  private static final String UNBINDING_FIELD_PROVIDER =
      "GraphQL servlet unbinding field provider %s";
  private static final String UNBINDING_NULL_FIELD_PROVIDER =
      "GraphQL servlet is unbinding null fieldProvider.";

  private List<FieldProvider> fieldProviders;

  private ScheduledExecutorService scheduler;
  private Cache<String, Object> cache;
  private BundleContext bundleContext;
  private GraphQLProvider graphQLProvider;
  private ServiceRegistration<GraphQLProvider> graphQlProviderServiceReg;

  public GraphQLTransformer(BundleContext bundleContext, List<FieldProvider> fieldProviders) {
    this.bundleContext = bundleContext;
    this.fieldProviders = fieldProviders == null? new ArrayList<>() : fieldProviders;

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

    if(!fieldProviders.isEmpty()) {
      triggerSchemaRefresh("Initializing transformer with field providers.");
    }
  }

  public void destroy() {
    scheduler.shutdownNow();
  }

  public void registerGraphQLProvider() {
    graphQLProvider = GraphQLTransformCommons.createGraphQLProvider(fieldProviders);
    graphQlProviderServiceReg =
        bundleContext.registerService(
            GraphQLProvider.class,
                graphQLProvider,
            null);
  }

  public void unregisterGraphQLProvider() {
    if (graphQlProviderServiceReg != null) {
      graphQlProviderServiceReg.unregister();
      graphQlProviderServiceReg = null;
    }
  }

  @Override
  public void handleEvent(Event event) {
    if (Events.REFRESH_SCHEMA.equals(event.getTopic())) {
      triggerSchemaRefresh((String) event.getProperty(Events.EVENT_REASON));
    }
  }

  private void refreshSchemaOnExpire(RemovalNotification notification) {
    if (notification.getCause() == RemovalCause.EXPIRED) {
      refreshSchema();
    }
  }

  // TODO: tbatie - 10/1/18 - We should decouple from graphql now that this is outside of a the
  // servlet. We could say "Definition change /refresh" or something
  private void triggerSchemaRefresh(String refreshReason) {
    LOGGER.trace("GraphQL schema refresh requested. Cause: {}", refreshReason);
    cache.put(Events.REFRESH_SCHEMA, true);
  }

  // Synchronized just in case the schema is still updating when another refresh is called
  // The performance decrease by the `synchronized` is negligible because of the periodic cache
  // invalidation implementation
  private synchronized void refreshSchema() {
    LOGGER.trace("Refreshing GraphQL schema.");
    unregisterGraphQLProvider();
    registerGraphQLProvider();
    LOGGER.trace("Finished refreshing GraphQL schema.");
  }

  public void bindFieldProvider(FieldProvider fieldProvider) {
    triggerSchemaRefresh(String.format(BINDING_FIELD_PROVIDER, fieldProvider.getFieldType()));
  }

  public void unbindFieldProvider(FieldProvider fieldProvider) {
    String refreshReason =
        fieldProvider == null
            ? UNBINDING_NULL_FIELD_PROVIDER
            : String.format(UNBINDING_FIELD_PROVIDER, fieldProvider.getFieldType());

    triggerSchemaRefresh(refreshReason);
  }
}
