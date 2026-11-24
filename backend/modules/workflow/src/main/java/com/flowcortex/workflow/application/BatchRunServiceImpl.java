package com.flowcortex.workflow.application;

import com.flowcortex.shared.web.ConflictException;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.shared.web.PageView;
import com.flowcortex.shared.web.UnprocessableEntityException;
import com.flowcortex.workflow.api.batch.BatchFormat;
import com.flowcortex.workflow.api.batch.BatchRequestException;
import com.flowcortex.workflow.api.batch.BatchRowView;
import com.flowcortex.workflow.api.batch.BatchRunService;
import com.flowcortex.workflow.api.batch.BatchView;
import com.flowcortex.workflow.api.batch.CreateBatch;
import com.flowcortex.workflow.domain.BatchRun;
import com.flowcortex.workflow.domain.BatchRunRow;
import com.flowcortex.workflow.domain.RunStatus;
import com.flowcortex.workflow.domain.Workflow;
import com.flowcortex.workflow.domain.WorkflowRun;
import com.flowcortex.workflow.domain.WorkflowStep;
import com.flowcortex.workflow.infrastructure.BatchRowCounts;
import com.flowcortex.workflow.infrastructure.BatchRunRepository;
import com.flowcortex.workflow.infrastructure.BatchRunRepository.BatchWithWorkflowName;
import com.flowcortex.workflow.infrastructure.BatchRunRowRepository;
import com.flowcortex.workflow.infrastructure.WorkflowRepository;
import com.flowcortex.workflow.infrastructure.WorkflowRunRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Implementation of {@link BatchRunService}. Every status change is one conditional write in the repositories, so a
 * member's pause or cancel and the dispatcher's own writes each apply only to the status they read. A batch stores
 * rows only: runs are started later, by the dispatcher, a few at a time.
 */
@Service
class BatchRunServiceImpl implements BatchRunService {

    /** A member may hold this many {@code running} or {@code paused} batches at once. */
    static final int MAX_UNFINISHED_BATCHES = 5;

    private static final String RETRY_SUFFIX = " (retry)";
    private static final String RETRY_NAME = "Retry";
    /** The longest stored name is 200 characters; the suffix is 8 of them. */
    private static final int MAX_RETRIED_NAME_CODE_POINTS = 192;
    private static final String PARENT_INDEX = "uq_batch_run_parent";
    private static final String CREATE_LOCK = "SELECT pg_advisory_xact_lock(hashtext(?))";
    private static final String CREATE_LOCK_PREFIX = "workflow.batch.create:";

    private final BatchRunRepository batches;
    private final BatchRunRowRepository rows;
    private final WorkflowRepository workflows;
    private final WorkflowRunRepository runs;
    private final RunStartPreflight preflight;
    private final StepExecutorRegistry registry;
    private final TransactionTemplate transactions;
    private final ApplicationEventPublisher events;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    BatchRunServiceImpl(BatchRunRepository batches, BatchRunRowRepository rows, WorkflowRepository workflows,
            WorkflowRunRepository runs, RunStartPreflight preflight, StepExecutorRegistry registry,
            TransactionTemplate transactions, ApplicationEventPublisher events, JdbcTemplate jdbc, Clock clock) {
        this.batches = batches;
        this.rows = rows;
        this.workflows = workflows;
        this.runs = runs;
        this.preflight = preflight;
        this.registry = registry;
        this.transactions = transactions;
        this.events = events;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public BatchView create(UUID callerId, UUID workflowId, CreateBatch request) {
        List<String> inputs;
        int concurrency;
        try {
            inputs = BatchInput.parse(request.rows(), request.content(), format(request.format()),
                    request.skipFirstLine());
            concurrency = BatchInput.concurrency(request.concurrency());
        } catch (BatchInput.Invalid invalid) {
            throw rejected(invalid);
        }
        Workflow workflow = preflight.resolve(callerId, workflowId);
        checkCanStart(callerId, workflow, request.acknowledgeCost());
        return store(callerId, workflow, null, cleanName(request.name()), concurrency, request.acknowledgeCost(),
                inputs, null);
    }

    @Override
    @Transactional(readOnly = true)
    public PageView<BatchView> list(UUID callerId, int page, int size) {
        Page<BatchWithWorkflowName> found = batches.findPageByOwner(callerId, null, PageRequest.of(page, size));
        Map<UUID, BatchRowCounts> counts = countsFor(found.stream().map(row -> row.batch().getId()).toList());
        return new PageView<>(found.stream()
                .map(row -> view(row.batch(), row.workflowName(), counts.get(row.batch().getId())))
                .toList(), page, size, found.getTotalElements());
    }

    @Override
    @Transactional(readOnly = true)
    public BatchView get(UUID callerId, UUID batchId) {
        return view(owned(callerId, batchId));
    }

    @Override
    @Transactional(readOnly = true)
    public PageView<BatchRowView> rows(UUID callerId, UUID batchId, String status, int page, int size) {
        BatchRun batch = owned(callerId, batchId);
        String stored = status == null ? null : BatchStatus.Row.parseReported(status)
                .orElseThrow(() -> new BatchRequestException("status",
                        new LocalizedMessage("workflow.batch.status.invalid")))
                .value();
        Page<BatchRunRow> found = rows.findPage(batch.getId(), stored, PageRequest.of(page, size));
        return new PageView<>(found.stream().map(BatchRunServiceImpl::rowView).toList(), page, size,
                found.getTotalElements());
    }

    @Override
    @Transactional
    public BatchView pause(UUID callerId, UUID batchId) {
        if (batches.pause(batchId, callerId, BatchStatus.PAUSE_MEMBER) == 0) {
            throw new ConflictException(new LocalizedMessage("workflow.batch.notPausable",
                    owned(callerId, batchId).getStatus()));
        }
        return view(owned(callerId, batchId));
    }

    @Override
    @Transactional
    public BatchView resume(UUID callerId, UUID batchId) {
        if (batches.resume(batchId, callerId) == 0) {
            throw new ConflictException(new LocalizedMessage("workflow.batch.notPaused",
                    owned(callerId, batchId).getStatus()));
        }
        events.publishEvent(new BatchDispatchRequested(batchId));
        return view(owned(callerId, batchId));
    }

    @Override
    @Transactional
    public BatchView cancel(UUID callerId, UUID batchId) {
        if (batches.cancel(batchId, callerId, BatchStatus.CANCEL_MEMBER) == 0) {
            throw new ConflictException(new LocalizedMessage("workflow.batch.alreadyFinished",
                    owned(callerId, batchId).getStatus()));
        }
        rows.cancelOpen(batchId, clock.instant());
        events.publishEvent(new BatchDispatchRequested(batchId));
        return view(owned(callerId, batchId));
    }

    @Override
    public BatchView retry(UUID callerId, UUID batchId) {
        BatchRun parent = owned(callerId, batchId);
        if (parent.getFinishedAt() == null) {
            throw new ConflictException(new LocalizedMessage("workflow.batch.notFinished"));
        }
        throwIfRetried(parent.getId());
        List<BatchRunRow> failed = retryableRows(parent.getId());
        if (failed.isEmpty()) {
            throw new UnprocessableEntityException(new LocalizedMessage("workflow.batch.noFailedRows"),
                    "workflow.batch.noFailedRows");
        }
        Workflow workflow = preflight.resolve(callerId, parent.getWorkflowId());
        checkCanStart(callerId, workflow, parent.isAcknowledgeCost());
        try {
            return store(callerId, workflow, parent.getId(), retryName(parent.getName()), parent.getConcurrency(),
                    parent.isAcknowledgeCost(), failed.stream().map(BatchRunRow::getInput).toList(),
                    failed.stream().map(BatchRunRow::getRowIndex).toList());
        } catch (DataIntegrityViolationException e) {
            if (!violates(e, PARENT_INDEX)) {
                throw e;
            }
            throwIfRetried(parent.getId());
            throw e;
        }
    }

    /**
     * Everything a run start refuses, applied once for the whole batch, plus the two limits only a batch has:
     * its workflow's first step must take text, and a member holds a bounded number of unfinished batches.
     */
    private void checkCanStart(UUID ownerId, Workflow workflow, boolean acknowledgeCost) {
        List<WorkflowStep> steps = preflight.checkSteps(ownerId, workflow, acknowledgeCost);
        if (!steps.isEmpty() && registry.isRemote(steps.getFirst().getType())) {
            throw new UnprocessableEntityException(new LocalizedMessage("workflow.batch.textInputOnly"),
                    "workflow.batch.textInputOnly");
        }
        throwIfTooManyUnfinished(ownerId);
    }

    private void throwIfTooManyUnfinished(UUID ownerId) {
        if (batches.countUnfinishedByOwner(ownerId) >= MAX_UNFINISHED_BATCHES) {
            throw new ConflictException(new LocalizedMessage("workflow.batch.tooManyActive", MAX_UNFINISHED_BATCHES));
        }
    }

    /** Saves the batch with its rows in one transaction and asks for it to be dispatched once that commits. */
    private BatchView store(UUID ownerId, Workflow workflow, UUID parentId, String name, int concurrency,
            boolean acknowledgeCost, List<String> inputs, List<Integer> sourceIndexes) {
        UUID batchId = UUID.randomUUID();
        Instant now = clock.instant();
        BatchRun batch = new BatchRun(batchId, ownerId, workflow.getId(), parentId, name, (short) concurrency,
                acknowledgeCost, inputs.size(), now);
        List<BatchRunRow> newRows = IntStream.range(0, inputs.size())
                .mapToObj(index -> new BatchRunRow(UUID.randomUUID(), batchId, index,
                        sourceIndexes == null ? null : sourceIndexes.get(index), inputs.get(index)))
                .toList();
        transactions.executeWithoutResult(status -> {
            // The check in checkCanStart is a fast refusal only: parallel creates all pass it, so the limit is
            // enforced here, serialised per member until this transaction commits.
            jdbc.query(CREATE_LOCK, (RowCallbackHandler) row -> {
            }, CREATE_LOCK_PREFIX + ownerId);
            throwIfTooManyUnfinished(ownerId);
            batches.save(batch);
            rows.saveAll(newRows);
            events.publishEvent(new BatchDispatchRequested(batchId));
        });
        return new BatchView(batchId, workflow.getId(), workflow.getName(), name, BatchRun.RUNNING, concurrency,
                inputs.size(), inputs.size(), 0, 0, 0, 0, null, null, parentId, now, null);
    }

    /**
     * The parent's failed rows that are still worth another try, in row order. A row whose run was re-opened and has
     * since completed or been cancelled is moved to that status first, so it is counted as such and left out; a row
     * whose re-opened run is live is left out too, its own run is already doing the work.
     */
    private List<BatchRunRow> retryableRows(UUID parentId) {
        return transactions.execute(status -> {
            rows.resyncFailedFromRuns(parentId);
            List<BatchRunRow> failed = rows.findFailedOrdered(parentId);
            Set<UUID> runIds = failed.stream().map(BatchRunRow::getRunId).filter(id -> id != null)
                    .collect(Collectors.toSet());
            Map<UUID, RunStatus> runStatus = runs.findAllById(runIds).stream()
                    .collect(Collectors.toMap(WorkflowRun::getId, WorkflowRun::getStatus));
            return failed.stream()
                    .filter(row -> row.getRunId() == null
                            || runStatus.getOrDefault(row.getRunId(), RunStatus.ERROR) == RunStatus.ERROR)
                    .toList();
        });
    }

    private void throwIfRetried(UUID parentId) {
        List<UUID> children = jdbc.queryForList("SELECT id FROM batch_run WHERE parent_batch_id = ?", UUID.class,
                parentId);
        if (!children.isEmpty()) {
            throw new ConflictException(new LocalizedMessage("workflow.batch.alreadyRetried", children.getFirst()));
        }
    }

    private BatchRun owned(UUID callerId, UUID batchId) {
        return batches.findByIdAndOwnerId(batchId, callerId)
                .orElseThrow(() -> new NotFoundException(new LocalizedMessage("workflow.batch.notFound", batchId)));
    }

    private BatchView view(BatchRun batch) {
        String workflowName = workflows.findById(batch.getWorkflowId()).map(Workflow::getName).orElse(null);
        return view(batch, workflowName, countsFor(List.of(batch.getId())).get(batch.getId()));
    }

    private Map<UUID, BatchRowCounts> countsFor(List<UUID> batchIds) {
        if (batchIds.isEmpty()) {
            return Map.of();
        }
        return rows.countsFor(batchIds).stream().collect(Collectors.toMap(BatchRowCounts::batchId, Function.identity()));
    }

    private static BatchView view(BatchRun batch, String workflowName, BatchRowCounts counts) {
        BatchRowCounts known = counts == null ? new BatchRowCounts(batch.getId(), 0, 0, 0, 0, 0) : counts;
        return new BatchView(batch.getId(), batch.getWorkflowId(), workflowName, batch.getName(), batch.getStatus(),
                batch.getConcurrency(), batch.getTotalRows(), known.pending(), known.running(), known.completed(),
                known.failed(), known.cancelled(), batch.getPauseReason(), batch.getCancelReason(),
                batch.getParentBatchId(), batch.getCreatedAt(), batch.getFinishedAt());
    }

    private static BatchRowView rowView(BatchRunRow row) {
        return new BatchRowView(row.getRowIndex(), row.getSourceRowIndex(), row.getInput(),
                BatchStatus.Row.of(row.getStatus()).reported().value(), row.getRunId(), row.getErrorCode(),
                row.getErrorMessage(), row.getStartedAt(), row.getFinishedAt());
    }

    private static BatchInput.Format format(BatchFormat format) {
        return format == null ? null : switch (format) {
            case CSV -> BatchInput.Format.CSV;
            case JSON -> BatchInput.Format.JSON;
            case JSONL -> BatchInput.Format.JSONL;
        };
    }

    private static String cleanName(String name) {
        if (name == null) {
            return null;
        }
        String stripped = name.strip();
        return stripped.isEmpty() ? null : stripped;
    }

    /** {@code <name> (retry)} with the name cut so the whole fits the stored length, or {@code Retry} with none. */
    private static String retryName(String parentName) {
        if (parentName == null) {
            return RETRY_NAME;
        }
        int end = parentName.codePointCount(0, parentName.length()) > MAX_RETRIED_NAME_CODE_POINTS
                ? parentName.offsetByCodePoints(0, MAX_RETRIED_NAME_CODE_POINTS) : parentName.length();
        return parentName.substring(0, end) + RETRY_SUFFIX;
    }

    private static boolean violates(Throwable failure, String constraint) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause.getMessage() != null && cause.getMessage().contains(constraint)) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }

    private static BatchRequestException rejected(BatchInput.Invalid invalid) {
        LocalizedMessage detail = switch (invalid.reason()) {
            case EXACTLY_ONE_INPUT -> new LocalizedMessage("workflow.batch.input.exactlyOne");
            case NOT_A_JSON_ARRAY -> new LocalizedMessage("workflow.batch.input.notJsonArray");
            case NO_ROWS -> new LocalizedMessage("workflow.batch.input.noRows");
            case TOO_MANY_ROWS -> new LocalizedMessage("workflow.batch.input.tooManyRows", BatchInput.MAX_ROWS);
            case ROW_TOO_LONG -> new LocalizedMessage("workflow.batch.input.rowTooLong", invalid.rowIndex(),
                    BatchInput.MAX_ROW_LENGTH);
            case TOO_LONG -> new LocalizedMessage("workflow.batch.input.tooLong", BatchInput.MAX_TOTAL_LENGTH);
            case CONCURRENCY_OUT_OF_RANGE -> new LocalizedMessage("workflow.batch.concurrency.invalid",
                    1, BatchInput.MAX_CONCURRENCY);
        };
        return new BatchRequestException(invalid.field(), detail);
    }
}
