package com.flowcortex.cvcheck.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CvJobPostingRepository {

    private final NamedParameterJdbcTemplate jdbc;

    CvJobPostingRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public int upsertForRole(String roleKey, List<JobPosting> postings, Instant syncedAt) {
        if (postings.isEmpty()) {
            return 0;
        }
        String sql = """
                INSERT INTO cv_job_postings
                    (source, external_id, role_key, title, company, location, salary,
                     work_mode, tags, description, apply_url, published_at, synced_at)
                VALUES
                    (:source, :externalId, :roleKey, :title, :company, :location, :salary,
                     :workMode, :tags, :description, :applyUrl, :publishedAt, :syncedAt)
                ON CONFLICT (source, external_id) DO UPDATE SET
                    role_key = EXCLUDED.role_key,
                    title = EXCLUDED.title,
                    company = EXCLUDED.company,
                    location = EXCLUDED.location,
                    salary = EXCLUDED.salary,
                    work_mode = EXCLUDED.work_mode,
                    tags = EXCLUDED.tags,
                    description = EXCLUDED.description,
                    apply_url = EXCLUDED.apply_url,
                    published_at = EXCLUDED.published_at,
                    synced_at = EXCLUDED.synced_at
                """;
        Timestamp syncedTs = Timestamp.from(syncedAt);
        MapSqlParameterSource[] batch = postings.stream()
                .map(p -> new MapSqlParameterSource()
                        .addValue("source", p.source())
                        .addValue("externalId", p.externalId())
                        .addValue("roleKey", roleKey)
                        .addValue("title", p.title())
                        .addValue("company", p.company())
                        .addValue("location", p.location())
                        .addValue("salary", p.salary())
                        .addValue("workMode", p.workMode())
                        .addValue("tags", String.join(",", p.tags()))
                        .addValue("description", p.description())
                        .addValue("applyUrl", p.applyUrl())
                        .addValue("publishedAt", p.publishedAt() == null ? null : Timestamp.from(p.publishedAt()))
                        .addValue("syncedAt", syncedTs))
                .toArray(MapSqlParameterSource[]::new);
        return jdbc.batchUpdate(sql, batch).length;
    }

    public int deleteStale(Instant olderThan) {
        return jdbc.update("DELETE FROM cv_job_postings WHERE synced_at < :cutoff",
                new MapSqlParameterSource("cutoff", Timestamp.from(olderThan)));
    }

    public List<JobPosting> findFreshByRoleKey(String roleKey, Instant minSyncedAt, int limit) {
        String sql = """
                SELECT source, external_id, title, company, location, salary, work_mode, tags, description, apply_url, published_at
                FROM cv_job_postings
                WHERE role_key = :roleKey AND synced_at >= :minSyncedAt
                ORDER BY COALESCE(published_at, synced_at) DESC
                LIMIT :limit
                """;
        return jdbc.query(sql,
                new MapSqlParameterSource("roleKey", roleKey)
                        .addValue("minSyncedAt", Timestamp.from(minSyncedAt))
                        .addValue("limit", limit),
                CvJobPostingRepository::mapRow);
    }

    public Optional<Instant> lastSyncedAtForRole(String roleKey) {
        List<Timestamp> rows = jdbc.query(
                "SELECT MAX(synced_at) FROM cv_job_postings WHERE role_key = :roleKey",
                new MapSqlParameterSource("roleKey", roleKey),
                (rs, rowNum) -> rs.getTimestamp(1));
        if (rows.isEmpty() || rows.getFirst() == null) {
            return Optional.empty();
        }
        return Optional.of(rows.getFirst().toInstant());
    }

    public List<String> findDistinctRoleKeys(int limit) {
        return jdbc.query(
                "SELECT role_key FROM cv_job_postings WHERE role_key <> '' GROUP BY role_key ORDER BY MAX(synced_at) DESC LIMIT :limit",
                new MapSqlParameterSource("limit", limit),
                (rs, rowNum) -> rs.getString(1));
    }

    private static JobPosting mapRow(ResultSet rs, int rowNum) throws SQLException {
        String rawTags = rs.getString("tags");
        List<String> tags = (rawTags == null || rawTags.isBlank()) ? List.of()
                : Arrays.stream(rawTags.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
        Timestamp pub = rs.getTimestamp("published_at");
        return new JobPosting(
                rs.getString("source"),
                rs.getString("external_id"),
                rs.getString("title"),
                rs.getString("company"),
                rs.getString("location"),
                rs.getString("salary"),
                rs.getString("work_mode"),
                tags,
                rs.getString("description"),
                rs.getString("apply_url"),
                pub == null ? null : pub.toInstant());
    }
}
