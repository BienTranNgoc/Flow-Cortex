package com.flowcortex.app.trash;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.AbstractPostgresBootTest;
import com.flowcortex.trash.application.TrashExpiryReminder;
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

/** With the switch off a pass returns before it reads an entry, so an entry due for a reminder is left unclaimed. */
@SpringBootTest(properties = "flowcortex.trash.enabled=false")
@Import(TrashTestProviders.class)
class TrashExpiryReminderSwitchedOffIT extends AbstractPostgresBootTest {

    @Autowired
    private TrashExpiryReminder reminder;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TrashTestProviders.SettableClock clock;

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM trash_entry");
    }

    @Test
    void switchOffSendsNothing() {
        Instant now = Instant.parse("2026-11-15T02:00:00Z");
        clock.set(now);
        UUID owner = UUID.randomUUID();
        jdbc.update("INSERT INTO trash_entry (id, item_type, item_id, owner_id, deleted_by, name, name_key, "
                + "location_code, deleted_at) VALUES (?, 'demo', 'old', ?, ?, 'Item', 'item', ?, ?)",
                UUID.randomUUID(), owner, owner, TrashTestProviders.LOCATION,
                Timestamp.from(now.minus(Duration.ofDays(40))));

        reminder.run();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM trash_entry WHERE reminded_at IS NOT NULL",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification WHERE user_id = ?", Integer.class, owner))
                .isZero();
    }
}
