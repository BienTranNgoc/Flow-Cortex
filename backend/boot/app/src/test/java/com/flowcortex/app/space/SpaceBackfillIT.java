package com.flowcortex.app.space;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.AbstractPostgresBootTest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The personal-space backfill migration, re-run against seeded rows: Flyway already applied it at
 * boot, so the test executes the same classpath SQL inside the test transaction, which rolls every
 * seeded and updated row back. Assertions target the seeded accounts and assets, except the re-run,
 * which must change nothing anywhere.
 */
@Testcontainers
@SpringBootTest
@Transactional
class SpaceBackfillIT extends AbstractPostgresBootTest {

    private static final String MIGRATION = "db/migration/V2026.11.08.003__space_backfill.sql";

    @Autowired
    private JdbcTemplate jdbc;

    private String backfill;
    private UUID confirmedA;
    private UUID confirmedB;
    private UUID unconfirmed;
    private UUID alreadyHasSpace;
    private UUID existingSpace;
    private UUID assetA1;
    private UUID assetA2;
    private UUID assetOfUnconfirmed;
    private UUID assetOfNobody;

    @BeforeEach
    void seed() throws IOException {
        try (InputStream in = new ClassPathResource(MIGRATION).getInputStream()) {
            backfill = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        confirmedA = user(true);
        confirmedB = user(true);
        unconfirmed = user(false);
        alreadyHasSpace = user(true);
        existingSpace = UUID.randomUUID();
        jdbc.update("INSERT INTO space (id, owner_id, is_default, created_at) VALUES (?, ?, true, ?)",
                existingSpace, alreadyHasSpace, Timestamp.from(Instant.now()));
        assetA1 = asset(confirmedA);
        assetA2 = asset(confirmedA);
        assetOfUnconfirmed = asset(unconfirmed);
        assetOfNobody = asset(UUID.randomUUID());
    }

    @Test
    void everyConfirmedAccountGetsOneSpaceWithItsManagerMembership() {
        jdbc.update(backfill);

        for (UUID user : List.of(confirmedA, confirmedB)) {
            assertThat(spaceIds(user)).hasSize(1);
            assertThat(members(spaceIds(user).get(0))).containsExactly(Map.entry(user, "manager"));
        }
    }

    @Test
    void unconfirmedAccountGetsNoSpace() {
        jdbc.update(backfill);

        assertThat(spaceIds(unconfirmed)).isEmpty();
        assertThat(count("SELECT count(*) FROM space_member WHERE user_id = ?", unconfirmed)).isZero();
    }

    @Test
    void accountAlreadyHoldingASpaceIsNotDuplicated() {
        jdbc.update(backfill);

        assertThat(spaceIds(alreadyHasSpace)).containsExactly(existingSpace);
        assertThat(members(existingSpace)).containsExactly(Map.entry(alreadyHasSpace, "manager"));
    }

    @Test
    void assetsAreAssignedToTheirOwnersSpaceAndOthersStayNull() {
        jdbc.update(backfill);

        UUID spaceOfA = spaceIds(confirmedA).get(0);
        assertThat(assetSpace(assetA1)).isEqualTo(spaceOfA);
        assertThat(assetSpace(assetA2)).isEqualTo(spaceOfA);
        assertThat(assetSpace(assetOfUnconfirmed)).isNull();
        assertThat(assetSpace(assetOfNobody)).isNull();
    }

    @Test
    void rerunChangesNoRowAnywhere() {
        jdbc.update(backfill);
        List<Map<String, Object>> spaces = jdbc.queryForList("SELECT * FROM space ORDER BY id");
        List<Map<String, Object>> members =
                jdbc.queryForList("SELECT * FROM space_member ORDER BY space_id, user_id");
        List<Map<String, Object>> assets = jdbc.queryForList("SELECT id, space_id FROM media_asset ORDER BY id");

        jdbc.update(backfill);

        assertThat(jdbc.queryForList("SELECT * FROM space ORDER BY id")).isEqualTo(spaces);
        assertThat(jdbc.queryForList("SELECT * FROM space_member ORDER BY space_id, user_id")).isEqualTo(members);
        assertThat(jdbc.queryForList("SELECT id, space_id FROM media_asset ORDER BY id")).isEqualTo(assets);
    }

    @Test
    void migrationTextNamesNoHelpCenterTable() {
        assertThat(backfill.toLowerCase()).doesNotContain("support");
    }

    private UUID user(boolean confirmed) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO app_user (id, owner_id, email, password_hash, name, role, status, created_at,"
                + " updated_at, email_confirmed_at) VALUES (?, ?, ?, 'x', 'Seeded', 'USER', 'ACTIVE', ?, ?, ?)",
                id, id, id + "@space-backfill.test", now, now, confirmed ? now : null);
        return id;
    }

    private UUID asset(UUID owner) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO media_asset (id, owner_id, kind, bucket, object_key, mime_type, bytes, status,"
                + " created_at) VALUES (?, ?, 'IMAGE', 'b', ?, 'image/png', 1, 'READY', ?)",
                id, owner, id.toString(), Timestamp.from(Instant.now()));
        return id;
    }

    private List<UUID> spaceIds(UUID owner) {
        return jdbc.queryForList("SELECT id FROM space WHERE owner_id = ? AND is_default", UUID.class, owner);
    }

    private List<Map.Entry<UUID, String>> members(UUID space) {
        return jdbc.query("SELECT user_id, role FROM space_member WHERE space_id = ?",
                (rs, i) -> Map.entry(rs.getObject("user_id", UUID.class), rs.getString("role")), space);
    }

    private UUID assetSpace(UUID asset) {
        return jdbc.queryForObject("SELECT space_id FROM media_asset WHERE id = ?", UUID.class, asset);
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }
}
