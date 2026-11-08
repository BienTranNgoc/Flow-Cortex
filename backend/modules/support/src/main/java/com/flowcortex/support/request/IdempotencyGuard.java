package com.flowcortex.support.request;

import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.support.web.SupportProblemException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Stores a create or reply once per {@code Idempotency-Key} and actor for 24 hours. The claim is written in the
 * same transaction as the write it guards, so a concurrent repeat waits for the first and then finds its result.
 */
@Component
class IdempotencyGuard {

    static final Duration WINDOW = Duration.ofHours(24);

    private final NamedParameterJdbcTemplate jdbc;

    IdempotencyGuard(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The header value as a key: absent stays absent, anything but a UUID is refused. */
    static Optional<String> key(String header) {
        if (header == null || header.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(header.trim()).toString());
        } catch (IllegalArgumentException e) {
            throw new SupportProblemException(HttpStatus.BAD_REQUEST, "validation-failed",
                    new LocalizedMessage("support.idempotency.keyInvalid"))
                    .withFieldErrors(java.util.Map.of("Idempotency-Key",
                            new LocalizedMessage("support.idempotency.keyField")));
        }
    }

    /**
     * The result already stored under the key within the window for the same {@code kind} and {@code targetId}
     * (the request replied to, null for a create).
     * @throws SupportProblemException 409 {@code idempotency-key-reused} if the key was used for another write
     */
    Optional<UUID> find(UUID actorId, String key, String kind, UUID targetId, Instant now) {
        return stored(actorId, key, Timestamp.from(now.minus(WINDOW)))
                .map(earlier -> earlier.resultFor(kind, targetId));
    }

    /**
     * Claims the key for {@code resultId}; call it first in the guarded transaction.
     * @return the result stored by an earlier or concurrent claim, or empty when this claim won
     * @throws SupportProblemException 409 {@code idempotency-key-reused} if that claim was for another write
     */
    Optional<UUID> claim(UUID actorId, String key, String kind, UUID targetId, UUID resultId, Instant now) {
        jdbc.update("DELETE FROM support_idempotency WHERE requester_id = :actor AND idem_key = :key "
                + "AND created_at <= :cutoff", params(actorId, key).addValue("cutoff", Timestamp.from(now.minus(WINDOW))));
        int claimed = jdbc.update("INSERT INTO support_idempotency (requester_id, idem_key, kind, target_id, "
                + "result_id, created_at) VALUES (:actor, :key, :kind, :target, :result, :now) "
                + "ON CONFLICT (requester_id, idem_key) DO NOTHING",
                params(actorId, key).addValue("kind", kind).addValue("target", targetId)
                        .addValue("result", resultId).addValue("now", Timestamp.from(now)));
        if (claimed == 1) {
            return Optional.empty();
        }
        return stored(actorId, key, null).map(earlier -> earlier.resultFor(kind, targetId));
    }

    private Optional<Stored> stored(UUID actorId, String key, Timestamp after) {
        return jdbc.query("SELECT kind, target_id, result_id FROM support_idempotency WHERE requester_id = :actor "
                + "AND idem_key = :key" + (after == null ? "" : " AND created_at > :cutoff"),
                params(actorId, key).addValue("cutoff", after),
                (rs, n) -> new Stored(rs.getString("kind"), rs.getObject("target_id", UUID.class),
                        rs.getObject("result_id", UUID.class))).stream().findFirst();
    }

    private record Stored(String kind, UUID targetId, UUID resultId) {

        UUID resultFor(String expectedKind, UUID expectedTarget) {
            if (!kind.equals(expectedKind) || !Objects.equals(targetId, expectedTarget)) {
                throw new SupportProblemException(HttpStatus.CONFLICT, "idempotency-key-reused",
                        "request".equals(kind) ? new LocalizedMessage("support.idempotency.reusedForRequest")
                                : new LocalizedMessage("support.idempotency.reusedForMessage"));
            }
            return resultId;
        }
    }

    int purgeOlderThan(Instant cutoff) {
        return jdbc.update("DELETE FROM support_idempotency WHERE created_at < :cutoff",
                new MapSqlParameterSource("cutoff", Timestamp.from(cutoff)));
    }

    private static MapSqlParameterSource params(UUID actorId, String key) {
        return new MapSqlParameterSource("actor", actorId).addValue("key", key);
    }
}
