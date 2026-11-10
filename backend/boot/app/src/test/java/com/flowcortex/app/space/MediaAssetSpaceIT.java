package com.flowcortex.app.space;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowcortex.AbstractPostgresBootTest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The asset-owner backfill, re-run against seeded rows, and the NOT NULL constraint it makes
 * possible. Flyway already applied both files at boot, so the backfill cases lift the constraint
 * inside the test transaction (Postgres DDL is transactional) before seeding rows without a space;
 * the rollback brings it back.
 */
@Testcontainers
@SpringBootTest
@Transactional
class MediaAssetSpaceIT extends AbstractPostgresBootTest {

    private static final String BACKFILL = "db/migration/V2026.11.10.020__media_asset_space_backfill.sql";

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void anAssetOwnerWithoutASpaceGetsOneWithItsManagerAndTheAssetCarriesIt() throws IOException {
        liftNotNull();
        UUID owner = UUID.randomUUID();
        UUID asset = asset(owner);

        jdbc.update(backfill());

        List<UUID> spaces = jdbc.queryForList("SELECT id FROM space WHERE owner_id = ? AND is_default", UUID.class,
                owner);
        assertThat(spaces).hasSize(1);
        assertThat(jdbc.query("SELECT user_id, role FROM space_member WHERE space_id = ?",
                (rs, i) -> Map.entry(rs.getObject("user_id", UUID.class), rs.getString("role")), spaces.get(0)))
                .containsExactly(Map.entry(owner, "manager"));
        assertThat(jdbc.queryForObject("SELECT space_id FROM media_asset WHERE id = ?", UUID.class, asset))
                .isEqualTo(spaces.get(0));
    }

    @Test
    void rerunningTheBackfillChangesNoRow() throws IOException {
        liftNotNull();
        asset(UUID.randomUUID());
        asset(UUID.randomUUID());
        jdbc.update(backfill());
        List<Map<String, Object>> spaces = jdbc.queryForList("SELECT * FROM space ORDER BY id");
        List<Map<String, Object>> members = jdbc.queryForList("SELECT * FROM space_member ORDER BY space_id, user_id");
        List<Map<String, Object>> assets = jdbc.queryForList("SELECT id, space_id FROM media_asset ORDER BY id");

        jdbc.update(backfill());

        assertThat(jdbc.queryForList("SELECT * FROM space ORDER BY id")).isEqualTo(spaces);
        assertThat(jdbc.queryForList("SELECT * FROM space_member ORDER BY space_id, user_id")).isEqualTo(members);
        assertThat(jdbc.queryForList("SELECT id, space_id FROM media_asset ORDER BY id")).isEqualTo(assets);
    }

    @Test
    void anInsertWithoutASpaceIsRefused() {
        assertThatThrownBy(() -> asset(UUID.randomUUID())).isInstanceOf(DataIntegrityViolationException.class);
    }

    private void liftNotNull() {
        jdbc.execute("ALTER TABLE media_asset ALTER COLUMN space_id DROP NOT NULL");
    }

    private static String backfill() throws IOException {
        try (InputStream in = new ClassPathResource(BACKFILL).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private UUID asset(UUID owner) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO media_asset (id, owner_id, kind, bucket, object_key, mime_type, bytes, status,"
                + " created_at) VALUES (?, ?, 'image', 'b', ?, 'image/png', 1, 'ready', ?)",
                id, owner, id.toString(), Timestamp.from(Instant.now()));
        return id;
    }
}
