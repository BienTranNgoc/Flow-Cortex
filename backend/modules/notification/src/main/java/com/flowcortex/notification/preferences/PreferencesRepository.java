package com.flowcortex.notification.preferences;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** The in-app choice of each member. No row means on. */
@Repository
class PreferencesRepository {

    private final NamedParameterJdbcTemplate jdbc;

    PreferencesRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    boolean inApp(UUID userId) {
        List<Boolean> found = jdbc.query("SELECT in_app FROM notification_preference WHERE user_id = :user",
                new MapSqlParameterSource("user", userId), (rs, row) -> rs.getBoolean("in_app"));
        return found.isEmpty() || found.getFirst();
    }

    /**
     * Creates the member's row if it is missing and then updates only {@code in_app}, so two requests that
     * name different choices cannot overwrite each other.
     */
    void setInApp(UUID userId, boolean inApp, Instant now) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("user", userId)
                .addValue("inApp", inApp).addValue("now", Timestamp.from(now));
        jdbc.update("INSERT INTO notification_preference (user_id, in_app, updated_at) VALUES (:user, true, :now) "
                + "ON CONFLICT (user_id) DO NOTHING", params);
        jdbc.update("UPDATE notification_preference SET in_app = :inApp, updated_at = :now WHERE user_id = :user",
                params);
    }
}
