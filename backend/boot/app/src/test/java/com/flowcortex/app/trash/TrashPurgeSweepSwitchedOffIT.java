package com.flowcortex.app.trash;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.AbstractPostgresBootTest;
import com.flowcortex.trash.application.TrashPurgeSweep;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/** With the switch off a pass returns before it reads an entry, so an expired one is neither purged nor asked about. */
@Testcontainers
@SpringBootTest(properties = "flowcortex.trash.enabled=false")
@Import(TrashTestProviders.class)
class TrashPurgeSweepSwitchedOffIT extends AbstractPostgresBootTest {

    @Autowired
    private TrashPurgeSweep sweep;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TrashTestProviders.SettableClock clock;

    @Autowired
    private TrashTestProviders.ScriptedProvider demoLateProvider;

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM trash_entry");
        demoLateProvider.reset();
    }

    @Test
    void switchOffPurgesNothing() {
        Instant now = Instant.parse("2026-11-20T10:00:00Z");
        clock.set(now);
        UUID owner = UUID.randomUUID();
        demoLateProvider.trash("old", owner);
        jdbc.update("INSERT INTO trash_entry (id, item_type, item_id, owner_id, deleted_by, name, name_key, "
                + "location_code, deleted_at) VALUES (?, 'demo_late', 'old', ?, ?, 'Item', 'item', ?, ?)",
                UUID.randomUUID(), owner, owner, TrashTestProviders.LOCATION,
                Timestamp.from(now.minus(Duration.ofDays(45))));

        sweep.run();

        assertThat(demoLateProvider.calls()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trash_entry", Integer.class)).isEqualTo(1);
        assertThat(demoLateProvider.hasRow("old")).isTrue();
    }
}
