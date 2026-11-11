package com.flowcortex.workflow.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowcortex.media.api.MediaJobInput;
import java.util.List;

/**
 * Rebuilds what a run's start stored as the input of its remote steps from the run's bound inputs,
 * because a claim overwrites a step's stored input with the resolved one. One input keyed
 * {@code media} is a JSON object (a single upload); any other binding set is a JSON array in
 * position order (reference images).
 */
final class RunInputShape {

    private static final String SINGLE_UPLOAD_KEY = "media";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RunInputShape() {}

    static String storedInput(List<MediaJobInput> bound) {
        if (bound.isEmpty()) {
            return null;
        }
        Object value = bound.size() == 1 && SINGLE_UPLOAD_KEY.equals(bound.getFirst().key()) ? bound.getFirst() : bound;
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Run inputs could not be serialized", e);
        }
    }
}
