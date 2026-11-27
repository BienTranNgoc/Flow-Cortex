package com.flowcortex.app.entity;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowcortex.AbstractPostgresBootTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The entity tables refuse rows no API path can produce; every seeded row rolls back. Handle
 * uniqueness and its release by trash are exercised through HTTP in {@code EntityApiIT}.
 */
@Testcontainers
@SpringBootTest
@Transactional
class EntitySchemaIT extends AbstractPostgresBootTest {

    @Autowired
    private JdbcTemplate jdbc;

    @ParameterizedTest
    @ValueSource(strings = {"Nodi", "a", "nó", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})
    void aHandleOutsideTheFormatIsRefused(String handle) {
        assertThatThrownBy(() -> entity(UUID.randomUUID(), handle, null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletedAtWithoutDeletedByIsRefused() {
        assertThatThrownBy(() -> entity(UUID.randomUUID(), "nodi", Timestamp.from(Instant.now())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aSecondVersionRowWithTheSameNumberIsRefused() {
        UUID id = UUID.randomUUID();
        entity(id, "nodi", null);
        version(id, 1);
        assertThatThrownBy(() -> version(id, 1)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aSecondUsageRowForTheSameRunAndEntityIsRefused() {
        UUID id = UUID.randomUUID();
        UUID run = UUID.randomUUID();
        entity(id, "nodi", null);
        usage(run, id);
        assertThatThrownBy(() -> usage(run, id)).isInstanceOf(DataIntegrityViolationException.class);
    }

    private void entity(UUID id, String handle, Timestamp deletedAt) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
                INSERT INTO space_entity (id, space_id, kind, handle, name, search_key, version, created_by,
                    created_at, updated_at, deleted_at)
                VALUES (?, ?, 'brand', ?, 'Nodi', 'nodi', 1, ?, ?, ?, ?)""",
                id, UUID.randomUUID(), handle, UUID.randomUUID(), now, now, deletedAt);
    }

    private void version(UUID entityId, int number) {
        jdbc.update("""
                INSERT INTO space_entity_version (entity_id, version, snapshot, created_at)
                VALUES (?, ?, '{}'::jsonb, ?)""",
                entityId, number, Timestamp.from(Instant.now()));
    }

    private void usage(UUID runId, UUID entityId) {
        jdbc.update("""
                INSERT INTO space_entity_run (run_id, entity_id, space_id, version, created_at)
                VALUES (?, ?, ?, 1, ?)""",
                runId, entityId, UUID.randomUUID(), Timestamp.from(Instant.now()));
    }
}
