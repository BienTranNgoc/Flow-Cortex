package com.flowcortex.onboarding.infrastructure;

import com.flowcortex.onboarding.domain.Answer;
import com.flowcortex.onboarding.domain.ProgressPatch;
import com.flowcortex.onboarding.domain.QuizRequest;
import com.flowcortex.onboarding.domain.StoredAnswers;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * A member's onboarding row. Every write path starts with {@link #ensureRow} inside the caller's
 * transaction, so a member's first writes never race; the guarded statements are single UPDATEs.
 */
@Repository
public class OnboardingRepository {

    private static final String COLUMNS = "purposes, source, ai_level, industries, content_types, usage_type, "
            + "completed_at, progress, builder_state, builder_completed_at";
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() { };

    /**
     * Applies the open rule in the WHERE clause so the check and the write are one statement; the
     * rule is mirrored by {@code OnboardingOpen.isOpen}. finishedAt is only ever stamped while absent.
     */
    private static final String WRITE_PROGRESS = """
            UPDATE user_onboarding
               SET progress = CASE WHEN progress->>'v' = '2' THEN progress ELSE '{}'::jsonb END
                              || CAST(:patch AS jsonb)
                              || CASE WHEN :finishing
                                       AND (progress->>'v' IS DISTINCT FROM '2' OR progress->>'finishedAt' IS NULL)
                                      THEN jsonb_build_object('finishedAt', CAST(:nowIso AS text))
                                      ELSE '{}'::jsonb END,
                   updated_at = :now
             WHERE user_id = :userId
               AND :switchOn
               AND ((progress->>'v' = '2' AND progress->>'finishedAt' IS NULL) OR NOT :firstRunDone)
            RETURNING progress
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final JsonMapper json = JsonMapper.builder().build();

    OnboardingRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Creates the member's empty row when missing; concurrent first writes both succeed. */
    public void ensureRow(UUID userId, Instant now) {
        jdbc.update("INSERT INTO user_onboarding (user_id, created_at, updated_at) VALUES (:userId, :now, :now) "
                + "ON CONFLICT (user_id) DO NOTHING", base(userId, now));
    }

    public Optional<OnboardingRecord> find(UUID userId) {
        return select(userId, "");
    }

    /** Like {@link #find} but holds the row until the transaction ends, serialising writers of one member. */
    public Optional<OnboardingRecord> findForUpdate(UUID userId) {
        return select(userId, " FOR UPDATE");
    }

    /** Writes every answer that is not absent: a skipped one clears the column. */
    public void updateAnswers(UUID userId, QuizRequest request, Instant now) {
        List<String> sets = new ArrayList<>();
        MapSqlParameterSource params = base(userId, now);
        scalar(sets, params, "source", "source", request.source());
        scalar(sets, params, "ai_level", "aiLevel", request.aiLevel());
        scalar(sets, params, "usage_type", "usageType", request.usageType());
        list(sets, params, "purposes", "purposes", request.purposes());
        list(sets, params, "industries", "industries", request.industries());
        list(sets, params, "content_types", "contentTypes", request.contentTypes());
        if (sets.isEmpty()) {
            return;
        }
        sets.add("updated_at = :now");
        jdbc.update("UPDATE user_onboarding SET " + String.join(", ", sets) + " WHERE user_id = :userId", params);
    }

    /** Stamps {@code completed_at} once and returns the stored value, the first one on every later call. */
    public Instant markCompleted(UUID userId, Instant now) {
        Timestamp stored = jdbc.queryForObject("UPDATE user_onboarding SET completed_at = COALESCE(completed_at, :now), "
                + "updated_at = :now WHERE user_id = :userId RETURNING completed_at", base(userId, now),
                Timestamp.class);
        return stored.toInstant();
    }

    /**
     * Merges the patch into the stored progress in one guarded statement; empty when the switch is
     * off or the flow is closed.
     */
    public Optional<ObjectNode> writeProgress(UUID userId, ProgressPatch patch, boolean switchOn,
            boolean firstRunDone, Instant now) {
        MapSqlParameterSource params = base(userId, now)
                .addValue("patch", patch.toJson(now).toString())
                .addValue("finishing", patch.finishing())
                .addValue("nowIso", now.toString())
                .addValue("switchOn", switchOn)
                .addValue("firstRunDone", firstRunDone);
        List<String> rows = jdbc.query(WRITE_PROGRESS, params, (rs, row) -> rs.getString("progress"));
        return rows.stream().findFirst().map(this::object);
    }

    public ObjectNode readProgress(UUID userId) {
        return find(userId).orElseGet(OnboardingRecord::empty).progress();
    }

    /** Clears answers, completion and progress; builder state is left alone. */
    public void reset(UUID userId, Instant now) {
        jdbc.update("UPDATE user_onboarding SET purposes = NULL, source = NULL, ai_level = NULL, industries = NULL, "
                + "content_types = NULL, usage_type = NULL, completed_at = NULL, progress = '{}'::jsonb, "
                + "updated_at = :now WHERE user_id = :userId", base(userId, now));
    }

    public void writeBuilderState(UUID userId, ObjectNode state, Instant now) {
        jdbc.update("UPDATE user_onboarding SET builder_state = CAST(:state AS jsonb), updated_at = :now "
                + "WHERE user_id = :userId", base(userId, now).addValue("state", state.toString()));
    }

    /** Stamps the builder completion once; a repeat keeps the first timestamp and the first state. */
    public OnboardingRecord completeBuilder(UUID userId, ObjectNode state, Instant now) {
        jdbc.update("UPDATE user_onboarding SET "
                + "builder_state = CASE WHEN builder_completed_at IS NULL THEN CAST(:state AS jsonb) "
                + "ELSE builder_state END, "
                + "builder_completed_at = COALESCE(builder_completed_at, :now), updated_at = :now "
                + "WHERE user_id = :userId", base(userId, now).addValue("state", state.toString()));
        return find(userId).orElseThrow(() -> new IllegalStateException("Onboarding row missing for " + userId));
    }

    private Optional<OnboardingRecord> select(UUID userId, String lock) {
        List<OnboardingRecord> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM user_onboarding WHERE user_id = :userId" + lock,
                new MapSqlParameterSource("userId", userId), (rs, row) -> map(rs));
        return rows.stream().findFirst();
    }

    private OnboardingRecord map(ResultSet rs) throws SQLException {
        StoredAnswers answers = new StoredAnswers(strings(rs.getString("purposes")), rs.getString("source"),
                rs.getString("ai_level"), strings(rs.getString("industries")), strings(rs.getString("content_types")),
                rs.getString("usage_type"));
        return new OnboardingRecord(answers, instant(rs.getTimestamp("completed_at")),
                object(rs.getString("progress")), object(rs.getString("builder_state")),
                instant(rs.getTimestamp("builder_completed_at")));
    }

    private List<String> strings(String column) {
        return column == null ? null : json.readValue(column, STRINGS);
    }

    private ObjectNode object(String column) {
        return (ObjectNode) json.readTree(column);
    }

    private static Instant instant(Timestamp column) {
        return column == null ? null : column.toInstant();
    }

    private static MapSqlParameterSource base(UUID userId, Instant now) {
        return new MapSqlParameterSource("userId", userId).addValue("now", Timestamp.from(now));
    }

    private static void scalar(List<String> sets, MapSqlParameterSource params, String column, String name,
            Answer<String> answer) {
        switch (answer) {
            case Answer.Absent<String> absent -> { }
            case Answer.Skipped<String> skipped -> sets.add(column + " = NULL");
            case Answer.Value<String> value -> {
                sets.add(column + " = :" + name);
                params.addValue(name, value.value());
            }
        }
    }

    private void list(List<String> sets, MapSqlParameterSource params, String column, String name,
            Answer<List<String>> answer) {
        switch (answer) {
            case Answer.Absent<List<String>> absent -> { }
            case Answer.Skipped<List<String>> skipped -> sets.add(column + " = NULL");
            case Answer.Value<List<String>> value -> {
                sets.add(column + " = CAST(:" + name + " AS jsonb)");
                params.addValue(name, json.writeValueAsString(value.value()));
            }
        }
    }
}
