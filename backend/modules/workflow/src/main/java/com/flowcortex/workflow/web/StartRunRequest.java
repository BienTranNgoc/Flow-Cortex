package com.flowcortex.workflow.web;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * The run's own input — step 1 resolves to this. Optional (not every workflow needs one).
 * Bounded like other request fields: {@code input} is unbounded {@code text} in the DB, so
 * without this limit an oversized body is an unchecked storage/DoS vector.
 *
 * <p>A workflow whose first step processes media takes {@code uploadId} (from {@code POST
 * /api/v1/uploads}) instead, plus the file's {@code originalName}, kept for display only; the
 * server trims it to 255 characters, so a longer name is accepted.
 *
 * <p>{@code acknowledgeCost} is the member's confirmation of the run's cost; absent means not confirmed.
 */
record StartRunRequest(@Size(max = 10_000) String input, UUID uploadId,
        @Size(min = 1, max = 10) List<UUID> uploadIds, @Size(max = 1024) String originalName,
        Boolean acknowledgeCost) {

    @AssertTrue(message = "Use uploadId or uploadIds, not both")
    @JsonIgnore
    boolean isUploadSelectionUnambiguous() {
        return uploadId == null || uploadIds == null;
    }

    @AssertTrue(message = "uploadIds must not contain duplicates")
    @JsonIgnore
    boolean isUploadOrderDistinct() {
        return uploadIds == null || uploadIds.stream().distinct().count() == uploadIds.size();
    }
}
