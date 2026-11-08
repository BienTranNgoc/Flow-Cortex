package com.flowcortex.support.config;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Whether the assistant takes questions: the environment switch AND the console switch in
 * {@code support_settings}, read per call so a console flip applies to the next question. The one
 * reader and writer of that table.
 */
@Component
public class AssistantSwitch {

    private static final Logger LOG = LoggerFactory.getLogger(AssistantSwitch.class);

    private final SupportProperties properties;
    private final JdbcTemplate jdbcTemplate;

    public AssistantSwitch(SupportProperties properties, JdbcTemplate jdbcTemplate) {
        this.properties = properties;
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean envEnabled() {
        return properties.assistant().enabled();
    }

    public boolean consoleEnabled() {
        List<Boolean> rows = jdbcTemplate.queryForList(
                "SELECT assistant_enabled FROM support_settings WHERE id = 1", Boolean.class);
        if (rows.isEmpty()) {
            LOG.warn("support.settings outcome=row_missing");
            return false;
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
        return jdbcTemplate.queryForObject("SELECT updated_by, updated_at FROM support_settings WHERE id = 1",
                (rs, rowNum) -> new Stamp(rs.getObject("updated_by", UUID.class),
                        rs.getTimestamp("updated_at").toInstant()));
    }

    public void setConsole(boolean enabled, UUID updatedBy, Instant updatedAt) {
        jdbcTemplate.update("UPDATE support_settings SET assistant_enabled = ?, updated_by = ?, updated_at = ? "
                + "WHERE id = 1", enabled, updatedBy, Timestamp.from(updatedAt));
        LOG.info("support.settings outcome=updated assistant_enabled={}", enabled);
    }
}
