package com.flowcortex.user.phone;

import com.flowcortex.user.phone.PhoneGateDecision.GateFacts;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Asks whether the phone wall holds a member. Reads the member's own role and number from the
 * database on every call, never from the access token, so a number verified in another tab or a role
 * changed by an admin applies to the next request. The switch binds at startup; off, nothing is read.
 */
@Component
public class PhoneGate {

    private static final String FACTS = "SELECT u.role, p.verified_at FROM app_user u "
            + "LEFT JOIN user_phone p ON p.user_id = u.id WHERE u.id = ?";

    private final boolean enabled;
    private final JdbcTemplate jdbc;

    PhoneGate(@Value("${flowcortex.phone-gate.enabled:false}") boolean enabled, JdbcTemplate jdbc) {
        this.enabled = enabled;
        this.jdbc = jdbc;
    }

    public boolean holds(UUID userId) {
        return enabled && PhoneGateDecision.holds(true, facts(userId));
    }

    /** {@code query}, not {@code queryForObject}: a deleted account is no row, not an exception. */
    private Optional<GateFacts> facts(UUID userId) {
        return jdbc.query(FACTS,
                (rs, row) -> new GateFacts(rs.getTimestamp("verified_at") != null, rs.getString("role")),
                userId).stream().findFirst();
    }
}
