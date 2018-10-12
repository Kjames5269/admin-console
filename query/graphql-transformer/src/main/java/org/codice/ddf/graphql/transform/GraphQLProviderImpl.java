package org.codice.ddf.graphql.transform;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLType;
import graphql.servlet.GraphQLMutationProvider;
import graphql.servlet.GraphQLProvider;
import graphql.servlet.GraphQLQueryProvider;
import graphql.servlet.GraphQLTypesProvider;

public class GraphQLProviderImpl  implements GraphQLProvider, GraphQLQueryProvider, GraphQLTypesProvider,
        GraphQLMutationProvider {

  private List<GraphQLFieldDefinition> queries;
  private List<GraphQLFieldDefinition> mutations;
  private List<GraphQLTypesProvider> typesProviders;

  public GraphQLProviderImpl(List<GraphQLFieldDefinition> queries,
          List<GraphQLFieldDefinition> mutations, List<GraphQLTypesProvider> typesProviders) {
    this.queries = queries;
    this.mutations = mutations;
    this.typesProviders = typesProviders;
  }

  @Override
  public Collection<GraphQLFieldDefinition> getQueries() {
    return queries;
  }

  @Override
  public Collection<GraphQLFieldDefinition> getMutations() {
    return mutations;
  }

  @Override
  public Collection<GraphQLType> getTypes() {
    return typesProviders.stream().map(GraphQLTypesProvider::getTypes).flatMap(Collection::stream).collect(
            Collectors.toList());
  }
}
