package org.codice.ddf.admin.query.graphql.strategy;

import graphql.GraphQLError;
import graphql.servlet.DefaultGraphQLErrorHandler;

public class GraphQLErrorHandler extends DefaultGraphQLErrorHandler {
  @Override
  protected boolean isClientError(GraphQLError error) {
    return error instanceof DataFetchingGraphQLError || super.isClientError(error);
  }
}
