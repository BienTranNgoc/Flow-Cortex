package com.flowcortex.workflow.api;

import java.util.List;

/** The short reading of a table result written after the run completed: a headline and up to three highlights. */
public record RunInsightView(String headline, List<String> highlights) {
}
