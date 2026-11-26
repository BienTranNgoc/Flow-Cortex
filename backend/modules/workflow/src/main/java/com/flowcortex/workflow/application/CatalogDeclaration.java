package com.flowcortex.workflow.application;

import com.flowcortex.llm.api.CapabilityDeclarations;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Component;

/**
 * Tells the model catalog which step types the engine runs: every registered executor type plus the human
 * steps, which have no executor. The catalog compares them with its capability rows.
 */
@Component
class CatalogDeclaration {

    CatalogDeclaration(StepExecutorRegistry registry, CapabilityDeclarations declarations) {
        Set<String> runnable = new TreeSet<>(registry.knownTypes());
        runnable.addAll(HumanSteps.TYPES);
        declarations.declare(runnable);
    }
}
