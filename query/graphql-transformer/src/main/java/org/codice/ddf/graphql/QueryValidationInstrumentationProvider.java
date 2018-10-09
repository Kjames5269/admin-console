package org.codice.ddf.graphql;

import com.google.common.collect.ImmutableList;

import graphql.analysis.MaxQueryComplexityInstrumentation;
import graphql.analysis.MaxQueryDepthInstrumentation;
import graphql.execution.instrumentation.ChainedInstrumentation;
import graphql.execution.instrumentation.Instrumentation;
import graphql.servlet.InstrumentationProvider;

public class QueryValidationInstrumentationProvider implements InstrumentationProvider {

  private static final int MAX_COMPLEXITY = 1000;
  private static final int MAX_QUERY_DEPTH = 100;

  private static final MaxQueryDepthInstrumentation MAX_QUERY_DEPTH_INSTRUMENTATION = new MaxQueryDepthInstrumentation(MAX_QUERY_DEPTH);
  private static final MaxQueryComplexityInstrumentation MAX_QUERY_COMPLEXITY_INSTRUMENTATION = new MaxQueryComplexityInstrumentation(MAX_COMPLEXITY);
  private static final ChainedInstrumentation ALL_INSTRUMENTATIONS = new ChainedInstrumentation(ImmutableList.of(MAX_QUERY_DEPTH_INSTRUMENTATION, MAX_QUERY_COMPLEXITY_INSTRUMENTATION));

  @Override
  public Instrumentation getInstrumentation() {
    return ALL_INSTRUMENTATIONS;
  }
}
