package com.flowcortex.app.drive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowcortex.AbstractPostgresBootTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Testcontainers;

/** The Drive tables refuse rows the item and Memory rules never produce; every seeded row rolls back. */
@Testcontainers
@SpringBootTest
@Transactional
class DriveSchemaIT extends AbstractPostgresBootTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void aTextItemWithAnObjectKeyIsRefused() {
        assertThatThrownBy(() -> item("text", "text", "hello", "drive/s/i", null, null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void anUploadItemWithoutAKeyIsRefused() {
        assertThatThrownBy(() -> item("image", "upload", null, null, null, null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aRunOutputItemWithoutItsRunIsRefused() {
        assertThatThrownBy(() -> item("image", "run_output", null, "runs/r/out.png", null, null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aRunOutputItemWithItsRunIsAccepted() {
        assertThatCode(() -> item("image", "run_output", null, "runs/r/out.png", UUID.randomUUID(), 2))
                .doesNotThrowAnyException();
    }

    @Test
    void aSecondMemoryRowWithTheSameKeyInTheSpaceIsRefused() {
        UUID space = UUID.randomUUID();
        memory(space, "step/1");
        assertThatThrownBy(() -> memory(space, "step/1")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theSameKeyInAnotherSpaceIsAccepted() {
        memory(UUID.randomUUID(), "step/1");
        memory(UUID.randomUUID(), "step/1");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM memory_result WHERE result_key = 'step/1'", Long.class))
                .isGreaterThanOrEqualTo(2L);
    }

    private void item(String contentType, String source, String text, String objectKey, UUID runId, Integer stepOrder) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
                INSERT INTO drive_item (id, space_id, owner_id, name, content_type, semantic_role, text_value, source,
                    bucket, object_key, run_id, step_order, created_at, updated_at)
                VALUES (?, ?, ?, 'n', ?, 'logo', ?, ?, ?, ?, ?, ?, ?, ?)""",
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), contentType, text, source,
                objectKey == null ? null : "flowcortex-media", objectKey, runId, stepOrder, now, now);
    }

    private void memory(UUID space, String key) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
                INSERT INTO memory_result (id, space_id, owner_id, run_id, run_step_id, step_order, result_key, kind,
                    title, size_bytes, result_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 1, ?, 'text', 't', 0, ?, ?, ?)""",
                UUID.randomUUID(), space, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), key, now, now, now);
    }
}
