package com.flowcortex.workflow.web;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.flowcortex.workflow.api.batch.BatchFormat;
import com.flowcortex.workflow.api.batch.CreateBatch;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Size;
import java.util.List;

/** The request bodies of the batch endpoints. */
final class BatchRunRequests {

    private BatchRunRequests() {
    }

    /**
     * Exactly one of {@code rows} (one run per non-empty entry) and {@code content} (the text of a file, split
     * by {@code format}, or by its shape when that is left out). The size limits bound what is read before the
     * rows are counted; a batch holds 1 to 500 rows after empty ones are dropped. {@code concurrency} is 1 to 10,
     * default 1; {@code acknowledgeCost} confirms the cost of every run, absent means not confirmed.
     */
    record CreateBatchRequest(
            @Size(max = 200) String name,
            @Size(max = 2_000_000) String content,
            @Size(max = 500) List<@Size(max = 10_000) String> rows,
            BatchFormat format, Boolean skipFirstLine, Integer concurrency, Boolean acknowledgeCost) {

        @AssertTrue(message = "Send either rows or content, not both and not neither")
        @JsonIgnore
        boolean isExactlyOneInput() {
            return (rows == null) != (content == null);
        }

        CreateBatch toCommand() {
            return new CreateBatch(rows, content, format, Boolean.TRUE.equals(skipFirstLine), name, concurrency,
                    Boolean.TRUE.equals(acknowledgeCost));
        }
    }
}
