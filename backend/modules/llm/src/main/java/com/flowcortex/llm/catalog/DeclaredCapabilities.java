package com.flowcortex.llm.catalog;

import com.flowcortex.llm.api.CapabilityDeclarations;
import java.util.Collection;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The capabilities the engine declared. The set is immutable and replaced as a whole by {@link #declare}, so
 * a reader sees either nothing declared yet or one complete declaration.
 */
@Component
class DeclaredCapabilities implements CapabilityDeclarations {

    private volatile Set<String> declared = Set.of();

    @Override
    public void declare(Collection<String> capabilities) {
        this.declared = Set.copyOf(capabilities);
    }

    Set<String> declared() {
        return declared;
    }
}
