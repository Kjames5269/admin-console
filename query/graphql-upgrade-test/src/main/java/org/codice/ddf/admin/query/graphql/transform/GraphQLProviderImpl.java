package org.codice.ddf.admin.query.graphql.transform;

import java.util.Collection;
import java.util.List;

import graphql.schema.GraphQLFieldDefinition;
import graphql.servlet.GraphQLMutationProvider;
import graphql.servlet.GraphQLProvider;
import graphql.servlet.GraphQLQueryProvider;

public class GraphQLProviderImpl  implements GraphQLProvider, GraphQLQueryProvider,
        GraphQLMutationProvider {

  private List<GraphQLFieldDefinition> queries;
  private List<GraphQLFieldDefinition> mutations;

  public GraphQLProviderImpl(List<GraphQLFieldDefinition> queries,
          List<GraphQLFieldDefinition> mutations) {
    this.queries = queries;
    this.mutations = mutations;
  }

  @Override
  public Collection<GraphQLFieldDefinition> getQueries() {
    return queries;
  }

  @Override
  public Collection<GraphQLFieldDefinition> getMutations() {
    return mutations;
  }
}
