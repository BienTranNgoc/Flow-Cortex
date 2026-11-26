package com.flowcortex.workflow.application;

import java.util.HashSet;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The step types a workflow definition may name, the set {@link KnownStepTypes} allows: every type the engine
 * runs plus the types a person decides. Public so the web constraints of the marketplace read it.
 */
@Component
public class MarketplaceStepTypeCatalogue {

    private final StepExecutorRegistry registry;

    MarketplaceStepTypeCatalogue(StepExecutorRegistry registry) {
        this.registry = registry;
    }

    public Set<String> types() {
        Set<String> types = new HashSet<>(registry.knownTypes());
        types.addAll(HumanSteps.TYPES);
        return types;
    }
}
