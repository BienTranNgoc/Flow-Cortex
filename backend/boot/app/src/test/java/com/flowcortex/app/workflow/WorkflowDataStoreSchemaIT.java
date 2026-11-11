package com.flowcortex.app.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The data store table and the load_template price row against the real schema. Every test runs in a
 * rolled-back transaction (a refused insert aborts it, so each refusal is its own test).
 */
@Testcontainers
@SpringBootTest
@Transactional
class WorkflowDataStoreSchemaIT extends AbstractWorkflowModuleIT {

    @Autowired
    private JdbcTemplate jdbc;

    private UUID insertWorkflow() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO workflow (id, owner_id, name, is_shared, created_at, updated_at)"
                + " VALUES (?, ?, 'schema-it', false, now(), now())", id, UUID.randomUUID());
        return id;
    }

    private void insertEntry(UUID workflowId, String key, String dataType, int sizeBytes) {
        jdbc.update("INSERT INTO workflow_data_store (id, workflow_id, key, data_type, value, size_bytes,"
                + " created_at, updated_at) VALUES (?, ?, ?, ?, '{}'::jsonb, ?, now(), now())",
                UUID.randomUUID(), workflowId, key, dataType, sizeBytes);
    }

    @Test
    void aSecondRowForOneWorkflowAndKeyIsRefused() {
        UUID workflowId = insertWorkflow();
        insertEntry(workflowId, "tone", "json", 2);

        assertThatThrownBy(() -> insertEntry(workflowId, "tone", "json", 2))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aKeyWithASpaceIsRefused() {
        UUID workflowId = insertWorkflow();

        assertThatThrownBy(() -> insertEntry(workflowId, "a b", "json", 2))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aKeyOf101CharactersIsRefused() {
        UUID workflowId = insertWorkflow();

        assertThatThrownBy(() -> insertEntry(workflowId, "k".repeat(101), "json", 2))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aDotOnlyKeyIsRefused() {
        UUID workflowId = insertWorkflow();

        assertThatThrownBy(() -> insertEntry(workflowId, ".", "json", 2))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aDoubleDotKeyIsRefused() {
        UUID workflowId = insertWorkflow();

        assertThatThrownBy(() -> insertEntry(workflowId, "..", "json", 2))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aKeyWithInnerDotsIsAccepted() {
        UUID workflowId = insertWorkflow();

        assertThatCode(() -> {
            insertEntry(workflowId, ".a", "json", 2);
            insertEntry(workflowId, "a..b", "json", 2);
        }).doesNotThrowAnyException();
    }

    @Test
    void anUppercaseTypeIsRefused() {
        UUID workflowId = insertWorkflow();

        assertThatThrownBy(() -> insertEntry(workflowId, "tone", "Template", 2))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aRecordedSizeOver524288IsRefused() {
        UUID workflowId = insertWorkflow();

        assertThatThrownBy(() -> insertEntry(workflowId, "tone", "json", 524_289))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aRowForAMissingWorkflowIsRefused() {
        assertThatThrownBy(() -> insertEntry(UUID.randomUUID(), "tone", "json", 2))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void loadTemplateIsPricedFree() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT estimator_kind, rate_card_ref, quantity_params FROM capability_pricing"
                        + " WHERE capability = 'load_template'");

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("estimator_kind")).isEqualTo("free");
        assertThat(rows.get(0).get("rate_card_ref")).isNull();
        assertThat(rows.get(0).get("quantity_params").toString()).isEqualTo("{}");
    }
}
