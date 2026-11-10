package com.flowcortex.workflow.web;

import com.flowcortex.workflow.application.WorkflowDataStore;
import java.time.Instant;
import java.util.List;

/** A workflow's entries without their values, newest update first, with the limits the store enforces. */
record DataEntryListResponse(List<DataEntrySummary> entries, int maxKeys, int maxValueBytes) {

    record DataEntrySummary(String key, String dataType, int sizeBytes, Instant createdAt, Instant updatedAt) {}

    static DataEntryListResponse from(WorkflowDataStore.Listing listing) {
        return new DataEntryListResponse(
                listing.entries().stream()
                        .map(entry -> new DataEntrySummary(entry.key(), entry.dataType(), entry.sizeBytes(),
                                entry.createdAt(), entry.updatedAt()))
                        .toList(),
                listing.maxKeys(), listing.maxValueBytes());
    }
}
