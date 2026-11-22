package com.flowcortex.support.request;

import com.flowcortex.media.api.StoredAttachment;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The reads and the delete behind the closed-request purge. Every call belongs to the one transaction of a
 * batch: the row locks taken by {@link #lockClosedBefore} last until it commits.
 */
@Repository
class RetentionRepository {

    private final NamedParameterJdbcTemplate jdbc;

    RetentionRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    record ClosedRequest(UUID id, long number) {
    }

    /**
     * Locks up to {@code limit} closed requests whose last activity is before {@code cutoff}, oldest first. A row
     * another instance holds locked is skipped, so two instances never purge the same request.
     */
    List<ClosedRequest> lockClosedBefore(Instant cutoff, int limit) {
        return jdbc.query("SELECT id, number FROM support_request WHERE status = 'closed' "
                + "AND last_activity_at < :cutoff ORDER BY last_activity_at, id LIMIT :limit FOR UPDATE SKIP LOCKED",
                new MapSqlParameterSource("cutoff", Timestamp.from(cutoff)).addValue("limit", limit),
                (rs, n) -> new ClosedRequest(rs.getObject("id", UUID.class), rs.getLong("number")));
    }

    /** The images of the given requests by request, in message then position order; one without images is absent. */
    Map<UUID, List<StoredAttachment>> attachmentsOf(Collection<UUID> requestIds) {
        if (requestIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<StoredAttachment>> byRequest = new LinkedHashMap<>();
        jdbc.query("SELECT m.request_id, a.bucket, a.object_key, a.content_type, a.size_bytes "
                + "FROM support_attachment a JOIN support_message m ON m.id = a.message_id "
                + "WHERE m.request_id IN (:requests) ORDER BY m.created_at, m.id, a.position",
                new MapSqlParameterSource("requests", requestIds), rs -> {
                    byRequest.computeIfAbsent(rs.getObject("request_id", UUID.class), id -> new ArrayList<>())
                            .add(new StoredAttachment(rs.getString("bucket"), rs.getString("object_key"),
                                    rs.getString("content_type"), rs.getLong("size_bytes")));
                });
        return byRequest;
    }

    /** Deletes the requests; their messages and attachment rows go with them. */
    int deleteRequests(Collection<UUID> requestIds) {
        if (requestIds.isEmpty()) {
            return 0;
        }
        return jdbc.update("DELETE FROM support_request WHERE id IN (:requests)",
                new MapSqlParameterSource("requests", requestIds));
    }
}
