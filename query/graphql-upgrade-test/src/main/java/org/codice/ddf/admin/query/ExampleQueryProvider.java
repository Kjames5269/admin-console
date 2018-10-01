package org.codice.ddf.admin.query;

import java.util.Collection;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import graphql.Scalars;
import graphql.schema.GraphQLFieldDefinition;
import graphql.servlet.GraphQLQueryProvider;

public class ExampleQueryProvider implements GraphQLQueryProvider {
  @Override
  public Collection<GraphQLFieldDefinition> getQueries() {
    GraphQLFieldDefinition definition = GraphQLFieldDefinition.newFieldDefinition()
            .name("helloWorld")
            .description("hello world")
            .type(Scalars.GraphQLString)
            .dataFetcher((env) -> "hello world!")
            .build();
    return Stream.of(definition).collect(Collectors.toList());
  }
}
