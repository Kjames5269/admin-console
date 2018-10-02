package org.codice.ddf.admin.query.graphql.strategy;

import java.util.List;

import org.codice.ddf.admin.api.report.ErrorMessage;
import org.codice.ddf.admin.query.graphql.FunctionDataFetcherException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import graphql.execution.AsyncExecutionStrategy;
import graphql.execution.DataFetcherExceptionHandlerParameters;
import graphql.execution.ExecutionPath;
import graphql.execution.ExecutionStrategy;
import graphql.execution.SimpleDataFetcherExceptionHandler;
import graphql.servlet.ExecutionStrategyProvider;

public class ExecutionStrategyProviderImpl implements ExecutionStrategyProvider {
  private static final Logger LOGGER = LoggerFactory.getLogger(ExecutionStrategyProviderImpl.class);

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

  public static class ExtendedEnhancedExecutionStrategy extends AsyncExecutionStrategy {

    ExtendedEnhancedExecutionStrategy() {
      super(new DataFetcherExceptionHandlerImpl());
    }
  }

  public static class DataFetcherExceptionHandlerImpl extends SimpleDataFetcherExceptionHandler {

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

  private static ExecutionPath listToExecutionPath(List<Object> path) {
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
}
