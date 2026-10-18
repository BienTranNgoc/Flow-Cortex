package com.flowcortex.workflow.api;

import java.util.List;
import java.util.UUID;

/**
 * A check that may refuse a run before anything is created. Every guard bean runs on each start,
 * in {@link org.springframework.core.annotation.Order} order, after the kill switch and the media
 * engine check and before the upload is resolved; the first guard that throws ends the start.
 */
public interface RunStartGuard {

    /** Throws the problem the member should see; returns normally to let the start continue. */
    void check(UUID ownerId, UUID workflowId, List<StepDef> steps, StartContext context);

    /** One step of the workflow being started, in step order. {@code config} is the raw JSON or {@code null}. */
    record StepDef(int order, String name, String type, String config, String inputRef) {
    }

    /** What the member sent with the start request beyond the input. */
    record StartContext(boolean acknowledgeCost) {
    }
}
