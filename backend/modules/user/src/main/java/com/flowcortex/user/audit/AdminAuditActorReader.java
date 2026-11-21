package com.flowcortex.user.audit;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Reads the acting admin's current email and name before the handler runs, so a self-edit does not
 * rewrite the identity the audit row records. Best-effort: a failure yields {@link ActorSnapshot#UNKNOWN}.
 */
@Component
class AdminAuditActorReader {

    private static final Logger LOG = LoggerFactory.getLogger(AdminAuditActorReader.class);

    private final NamedParameterJdbcTemplate jdbc;

    AdminAuditActorReader(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    ActorSnapshot read(UUID actorUserId) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT email, name FROM app_user WHERE id = :id", Map.of("id", actorUserId));
            if (rows == null || rows.isEmpty()) {
                return ActorSnapshot.UNKNOWN;
            }
            Map<String, Object> row = rows.get(0);
            return new ActorSnapshot((String) row.get("email"), (String) row.get("name"));
        } catch (RuntimeException ex) {
            LOG.warn("Admin audit actor snapshot for {} failed; the row is written without it", actorUserId, ex);
            return ActorSnapshot.UNKNOWN;
        }
    }
}
