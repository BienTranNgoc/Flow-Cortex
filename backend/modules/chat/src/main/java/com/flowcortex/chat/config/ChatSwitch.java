package com.flowcortex.chat.config;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Whether chat takes messages: the environment switch AND the console switch in {@code chat_settings},
 * read per call so a console flip applies to the next send. The one reader and writer of that table.
 */
@Component("chatSwitch")
public class ChatSwitch {

    private static final Logger LOG = LoggerFactory.getLogger(ChatSwitch.class);

    private final ChatProperties properties;
    private final JdbcTemplate jdbcTemplate;

    ChatSwitch(ChatProperties properties, JdbcTemplate jdbcTemplate) {
        this.properties = properties;
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean envEnabled() {
        return properties.enabled();
    }

    /** The row is inserted by the migration; a missing row reads as on, like the list always has. */
    public boolean consoleEnabled() {
        List<Boolean> rows = jdbcTemplate.queryForList("SELECT enabled FROM chat_settings WHERE id = 1",
                Boolean.class);
        if (rows.isEmpty()) {
            LOG.warn("chat.settings outcome=row_missing");
            return true;
        }
        return rows.getFirst();
    }

    public boolean available() {
        return envEnabled() && consoleEnabled();
    }

    /** Who last flipped the console switch (null when never) and when. */
    public record Stamp(UUID updatedBy, Instant updatedAt) {
    }

    public Stamp stamp() {
        return jdbcTemplate.queryForObject("SELECT updated_by, updated_at FROM chat_settings WHERE id = 1",
                (rs, rowNum) -> {
                    Timestamp at = rs.getTimestamp("updated_at");
                    return new Stamp(rs.getObject("updated_by", UUID.class), at == null ? null : at.toInstant());
                });
    }

    public void setConsole(boolean enabled, UUID updatedBy, Instant updatedAt) {
        jdbcTemplate.update("UPDATE chat_settings SET enabled = ?, updated_by = ?, updated_at = ? WHERE id = 1",
                enabled, updatedBy, Timestamp.from(updatedAt));
        LOG.info("chat.settings outcome=updated enabled={}", enabled);
    }
}
