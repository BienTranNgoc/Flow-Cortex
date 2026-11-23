package com.flowcortex.onboarding.domain;

import java.util.UUID;

/** The configured sample: a workflow, the input typed for it and the language that input is written in. */
public record StoredSample(UUID workflowId, String sampleInput, String sourceLocale) {

    /** The token a translation is stored under; it changes with any of the three fields. */
    public String sourceHash() {
        return SampleSourceHash.of(workflowId, sourceLocale, sampleInput);
    }
}
