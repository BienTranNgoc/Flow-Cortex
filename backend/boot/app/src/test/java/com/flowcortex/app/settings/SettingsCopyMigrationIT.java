package com.flowcortex.app.settings;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.AbstractPostgresBootTest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * The assistant-switch copy migration, re-run against seeded rows: Flyway already applied it at
 * boot, so the test executes the same classpath SQL inside the test transaction, which rolls every
 * change back.
 */
@SpringBootTest
@Transactional
class SettingsCopyMigrationIT extends AbstractPostgresBootTest {

    private static final String MIGRATION = "db/migration/V2026.11.11.181__settings_copy_support_switch.sql";
    private static final String KEY = "support.assistant.enabled";

    @Autowired
    private JdbcTemplate jdbc;

    private String copy;

    @BeforeEach
    void load() throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(MIGRATION)) {
            copy = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        jdbc.update("DELETE FROM app_setting WHERE key = ?", KEY);
    }

    @Test
    void aFlippedSwitchIsCopiedWithItsStamp() {
        UUID admin = UUID.randomUUID();
        Instant at = Instant.now().truncatedTo(ChronoUnit.MILLIS).minusSeconds(3600);
        jdbc.update("UPDATE support_settings SET assistant_enabled = false, updated_by = ?, updated_at = ? "
                + "WHERE id = 1", admin, Timestamp.from(at));

        jdbc.update(copy);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT value, updated_by, updated_at FROM app_setting WHERE key = ?", KEY);
        assertThat(row.get("value")).isEqualTo("false");
        assertThat(row.get("updated_by")).isEqualTo(admin);
        assertThat(((Timestamp) row.get("updated_at")).toInstant()).isEqualTo(at);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM support_settings", Integer.class)).isEqualTo(1);
    }

    @Test
    void theSeededNeverFlippedRowIsNotCopied() {
        jdbc.update("UPDATE support_settings SET assistant_enabled = true, updated_by = NULL WHERE id = 1");

        jdbc.update(copy);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_setting WHERE key = ?", Integer.class, KEY))
                .isZero();
    }
}
