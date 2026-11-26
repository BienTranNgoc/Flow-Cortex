package com.flowcortex.app.trash;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowcortex.AbstractPostgresBootTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The invariants the database itself enforces on the Trash index: one entry per item, and the shapes of
 * the type key, item id and name. Rows are inserted straight through JDBC, bypassing every Java check.
 */
@Testcontainers
@SpringBootTest
class TrashSchemaIT extends AbstractPostgresBootTest {

    private static final Instant DELETED_AT = Instant.parse("2026-11-15T10:00:00Z");

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM trash_entry");
    }

    @Test
    void aSecondEntryForTheSameItemIsRefused() {
        insert("demo", "1", "Bản nháp");

        assertThatThrownBy(() -> insert("demo", "1", "Bản nháp khác"))
                .isInstanceOf(DuplicateKeyException.class)
                .hasMessageContaining("uq_trash_entry_item");
    }

    @Test
    void theSameItemIdOfAnotherTypeIsAllowed() {
        insert("demo", "1", "Bản nháp");

        assertThatCode(() -> insert("demo_late", "1", "Bản nháp")).doesNotThrowAnyException();
        assertThat(count()).isEqualTo(2);
    }

    @Test
    void aMalformedTypeKeyIsRefused() {
        assertThatThrownBy(() -> insert("Demo", "1", "Bản nháp"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_trash_entry_type");
        assertThatThrownBy(() -> insert("d", "1", "Bản nháp"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_trash_entry_type");
        assertThat(count()).isZero();
    }

    @Test
    void aMalformedItemIdIsRefused() {
        assertThatThrownBy(() -> insert("demo", "a b", "Bản nháp"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_trash_entry_item");
        assertThatThrownBy(() -> insert("demo", "a".repeat(65), "Bản nháp"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(count()).isZero();
    }

    @Test
    void aNameOver200CharactersIsRefused() {
        assertThatThrownBy(() -> insert("demo", "1", "a".repeat(201)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_trash_entry_name");
        assertThatCode(() -> insert("demo", "2", "a".repeat(200))).doesNotThrowAnyException();
    }

    @Test
    void anEmptyNameIsRefused() {
        assertThatThrownBy(() -> insert("demo", "1", ""))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_trash_entry_name");
    }

    @Test
    void aNullNameIsAllowed() {
        assertThatCode(() -> insert("demo", "1", null)).doesNotThrowAnyException();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trash_entry WHERE name IS NULL", Integer.class))
                .isEqualTo(1);
    }

    private void insert(String type, String itemId, String name) {
        UUID owner = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO trash_entry (id, item_type, item_id, owner_id, deleted_by, name, name_key, location_code, deleted_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(), type, itemId, owner, owner, name, name == null ? "" : name.toLowerCase(),
                "trash.location.demo", Timestamp.from(DELETED_AT));
    }

    private int count() {
        return jdbc.queryForObject("SELECT count(*) FROM trash_entry", Integer.class);
    }
}
