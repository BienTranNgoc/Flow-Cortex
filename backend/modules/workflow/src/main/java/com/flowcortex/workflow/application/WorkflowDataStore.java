package com.flowcortex.workflow.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowcortex.shared.web.ConflictException;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.shared.web.UnprocessableEntityException;
import com.flowcortex.workflow.api.ProductVideoWorkflow;
import com.flowcortex.workflow.domain.WorkflowDataEntry;
import com.flowcortex.workflow.infrastructure.WorkflowDataEntryRepository;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A workflow's key-value store of JSON values: the owner manages it, steps such as {@code load_template} read it. */
@Service
public class WorkflowDataStore {

    private static final String DEFAULT_TYPE = "json";

    private static final ObjectMapper JSON = DataStoreJson.mapper();

    /** An entry with its value. */
    public record Entry(String key, JsonNode value, String dataType, int sizeBytes, Instant createdAt,
            Instant updatedAt) {}

    /** An entry as the list shows it, without the value. */
    public record Summary(String key, String dataType, int sizeBytes, Instant createdAt, Instant updatedAt) {}

    /** Every entry newest update first, with the limits the store enforces. */
    public record Listing(List<Summary> entries, int maxKeys, int maxValueBytes) {}

    /** One entry to place in a freshly created workflow. */
    public record Seed(String key, String dataType, JsonNode value) {}

    private final WorkflowDataEntryRepository entries;
    private final WorkflowWriteAccess writeAccess;

    WorkflowDataStore(WorkflowDataEntryRepository entries, WorkflowWriteAccess writeAccess) {
        this.entries = entries;
        this.writeAccess = writeAccess;
    }

    @Transactional(readOnly = true)
    public Listing list(Caller caller, UUID workflowId) {
        writeAccess.requireOwned(caller, workflowId);
        List<Summary> summaries = entries.findByWorkflowIdOrderByUpdatedAtDesc(workflowId).stream()
                .map(entry -> new Summary(entry.getKey(), entry.getDataType(), entry.getSizeBytes(),
                        entry.getCreatedAt(), entry.getUpdatedAt()))
                .toList();
        return new Listing(summaries, DataStoreRules.MAX_KEYS, DataStoreRules.MAX_VALUE_BYTES);
    }

    @Transactional(readOnly = true)
    public Entry get(Caller caller, UUID workflowId, String key) {
        writeAccess.requireOwned(caller, workflowId);
        return entries.findByWorkflowIdAndKey(workflowId, key)
                .map(WorkflowDataStore::view)
                .orElseThrow(() -> new NotFoundException(new LocalizedMessage("workflow.dataStore.keyNotFound")));
    }

    /** Creates the entry, or replaces its value and, when {@code dataType} is not {@code null}, its type. */
    @Transactional
    public Entry put(Caller caller, UUID workflowId, String key, JsonNode value, String dataType) {
        lockForWrite(caller, workflowId);
        return store(workflowId, key, value, dataType);
    }

    /** Idempotent: an absent key answers as a removed one. */
    @Transactional
    public void delete(Caller caller, UUID workflowId, String key) {
        lockForWrite(caller, workflowId);
        entries.deleteByWorkflowIdAndKey(workflowId, key);
    }

    /** The fixed Product Video workflow is shared, so a member would meet the owner refusal first; it is a 409 for all. */
    private void lockForWrite(Caller caller, UUID workflowId) {
        if (ProductVideoWorkflow.ID.equals(workflowId)) {
            throw new ConflictException(new LocalizedMessage("workflow.system.readOnly"));
        }
        writeAccess.lockForWrite(caller, workflowId);
    }

    /**
     * Fills a workflow the caller has just created in the same transaction, so there is no access check; the store
     * rules still apply to every entry.
     */
    @Transactional
    public void seed(UUID workflowId, List<Seed> seeds) {
        for (Seed seed : seeds) {
            store(workflowId, seed.key(), seed.value(), seed.dataType());
        }
    }

    private Entry store(UUID workflowId, String key, JsonNode value, String dataType) {
        WorkflowDataEntry existing = key == null ? null : entries.findByWorkflowIdAndKey(workflowId, key).orElse(null);
        DataStoreRules.Fault fault = DataStoreRules.check(key, dataType, value, existing != null,
                entries.countByWorkflowId(workflowId));
        if (fault != null) {
            throw new UnprocessableEntityException(fault.message(), fault.code(),
                    Map.of(fault.field(), fault.message()));
        }
        String text = write(value);
        int size = DataStoreRules.sizeOf(value);
        // Postgres keeps microseconds; a finer stamp would make a write answer differ from the next read.
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        WorkflowDataEntry saved;
        if (existing == null) {
            saved = entries.save(new WorkflowDataEntry(UUID.randomUUID(), workflowId, key,
                    dataType == null ? DEFAULT_TYPE : dataType, text, size, now));
        } else {
            existing.replace(text, dataType, size, now);
            saved = existing;
        }
        return view(saved);
    }

    private static Entry view(WorkflowDataEntry entry) {
        return new Entry(entry.getKey(), read(entry.getValue()), entry.getDataType(), entry.getSizeBytes(),
                entry.getCreatedAt(), entry.getUpdatedAt());
    }

    private static String write(JsonNode value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static JsonNode read(String text) {
        try {
            return JSON.readTree(text);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }
}
