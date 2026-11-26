package com.flowcortex.llm.api;

import java.util.List;
import java.util.Optional;

/** Read access to the code-defined model descriptors. */
public interface ModelDescriptors {

    /** The descriptor of this model id; empty for an id nobody described, never an error. */
    Optional<ModelDescriptor> describe(String id);

    /** Every descriptor, ordered by id. */
    List<ModelDescriptor> all();
}
