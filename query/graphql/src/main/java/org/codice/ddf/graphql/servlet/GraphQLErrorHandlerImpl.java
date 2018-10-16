package org.codice.ddf.graphql.servlet;

import graphql.GraphQLError;
import graphql.servlet.DefaultGraphQLErrorHandler;

public class GraphQLErrorHandlerImpl extends DefaultGraphQLErrorHandler {
  @Override
  protected boolean isClientError(GraphQLError error) {
    return error instanceof DataFetchingGraphQLError || super.isClientError(error);
  }
}
