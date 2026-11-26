package com.flowcortex.llm.api;

import java.util.Collection;

/** How a module tells the catalog which capabilities (step types) its code runs. */
public interface CapabilityDeclarations {

    /** Records the capabilities the caller runs; the catalog compares them with its capability rows. */
    void declare(Collection<String> capabilities);
}
