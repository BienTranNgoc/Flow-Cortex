package com.flowcortex.onboarding.infrastructure;

import com.flowcortex.onboarding.domain.SampleTranslations.Translation;
import com.flowcortex.onboarding.domain.StoredSample;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** The singleton onboarding sample (row 1, seeded by the migration) and its per-language input translations. */
@Repository
public class OnboardingSampleRepository {

    /** The stored row; {@code sample} is null while nothing is configured. */
    public record Row(StoredSample sample, UUID updatedBy, Instant updatedAt) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    OnboardingSampleRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Row read() {
        return jdbc.getJdbcTemplate().queryForObject(
                "SELECT workflow_id, sample_input, source_locale, updated_by, updated_at "
                        + "FROM onboarding_sample WHERE id = 1",
                (rs, row) -> {
                    UUID workflowId = rs.getObject("workflow_id", UUID.class);
                    StoredSample sample = workflowId == null ? null
                            : new StoredSample(workflowId, rs.getString("sample_input"),
                                    rs.getString("source_locale"));
                    return new Row(sample, rs.getObject("updated_by", UUID.class),
                            rs.getTimestamp("updated_at").toInstant());
                });
    }

    /** Sets the sample, or clears it when {@code sample} is null. */
    public void write(StoredSample sample, UUID adminId, Instant now) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("workflowId", sample == null ? null : sample.workflowId())
                .addValue("input", sample == null ? null : sample.sampleInput())
                .addValue("locale", sample == null ? null : sample.sourceLocale())
                .addValue("adminId", adminId)
                .addValue("now", Timestamp.from(now));
        jdbc.update("UPDATE onboarding_sample SET workflow_id = :workflowId, sample_input = :input, "
                + "source_locale = :locale, updated_by = :adminId, updated_at = :now WHERE id = 1", params);
    }

    public List<Translation> translations(String sourceHash) {
        return jdbc.query("SELECT source_hash, locale, input FROM onboarding_sample_translation "
                        + "WHERE source_hash = :hash",
                new MapSqlParameterSource("hash", sourceHash),
                (rs, row) -> new Translation(rs.getString("source_hash").trim(), rs.getString("locale"),
                        rs.getString("input")));
    }

    public void upsertTranslation(String sourceHash, String locale, String input, UUID adminId, Instant now) {
        jdbc.update("INSERT INTO onboarding_sample_translation (source_hash, locale, input, updated_by, updated_at) "
                + "VALUES (:hash, :locale, :input, :adminId, :now) "
                + "ON CONFLICT (source_hash, locale) DO UPDATE SET input = EXCLUDED.input, "
                + "updated_by = EXCLUDED.updated_by, updated_at = EXCLUDED.updated_at",
                new MapSqlParameterSource().addValue("hash", sourceHash).addValue("locale", locale)
                        .addValue("input", input).addValue("adminId", adminId).addValue("now", Timestamp.from(now)));
    }

    public void deleteTranslation(String sourceHash, String locale) {
        jdbc.update("DELETE FROM onboarding_sample_translation WHERE source_hash = :hash AND locale = :locale",
                new MapSqlParameterSource().addValue("hash", sourceHash).addValue("locale", locale));
    }
}
