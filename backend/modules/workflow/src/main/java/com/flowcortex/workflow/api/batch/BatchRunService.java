package com.flowcortex.workflow.api.batch;

import com.flowcortex.shared.web.PageView;
import java.util.UUID;

/**
 * A member's batches of runs of one workflow. Every method is scoped to the caller: another member's or an unknown
 * batch is a {@link com.flowcortex.shared.web.NotFoundException}, never a refusal that reveals it exists.
 */
public interface BatchRunService {

    /**
     * Stores a {@code running} batch with one {@code pending} row per input and asks for it to be dispatched.
     * @throws BatchRequestException (400) for input that breaks a size limit or does not parse
     * @throws com.flowcortex.shared.web.NotFoundException if the workflow is unknown or not visible to the caller
     * @throws com.flowcortex.shared.web.LocalizedStatusException 503 when a run start would be refused as
     *         unavailable, 400 for the Product Video workflow
     * @throws com.flowcortex.shared.web.UnprocessableEntityException when the workflow is incomplete or its first
     *         step takes an upload
     * @throws com.flowcortex.shared.web.ConflictException when the caller already has five unfinished batches
     */
    BatchView create(UUID callerId, UUID workflowId, CreateBatch request);

    /** The caller's batches, newest first. */
    PageView<BatchView> list(UUID callerId, int page, int size);

    BatchView get(UUID callerId, UUID batchId);

    /**
     * The batch's rows in row order, optionally of one status ({@code pending}, {@code running},
     * {@code completed}, {@code failed} or {@code cancelled}).
     * @throws BatchRequestException (400) for any other {@code status}
     */
    PageView<BatchRowView> rows(UUID callerId, UUID batchId, String status, int page, int size);

    /** @throws com.flowcortex.shared.web.ConflictException 409 unless the batch is {@code running} */
    BatchView pause(UUID callerId, UUID batchId);

    /** @throws com.flowcortex.shared.web.ConflictException 409 unless the batch is {@code paused} */
    BatchView resume(UUID callerId, UUID batchId);

    /**
     * Cancels the batch and its pending rows; live runs are asked to stop afterwards, by the dispatcher.
     * @throws com.flowcortex.shared.web.ConflictException 409 when the batch is finished or already cancelled
     */
    BatchView cancel(UUID callerId, UUID batchId);

    /**
     * Starts a new batch of the finished batch's failed rows. A row whose run was re-opened and has since
     * completed or been cancelled is counted as such and left out.
     * @throws com.flowcortex.shared.web.ConflictException 409 when the batch is not finished or was retried already
     * @throws com.flowcortex.shared.web.UnprocessableEntityException when no failed row is left to retry
     */
    BatchView retry(UUID callerId, UUID batchId);
}
