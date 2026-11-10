package com.flowcortex.asset.infrastructure;

import com.flowcortex.asset.domain.NewSpaceAsset;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Library assets. Uniqueness of the stored file and of the object is enforced by the table's two unique keys. */
@Repository
public class SpaceAssetStore {

    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    public SpaceAssetStore(NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Inserts the asset; false when either unique key already holds one, in which case the existing row is untouched. */
    public boolean insertIfAbsent(NewSpaceAsset asset) {
        return jdbc.update("INSERT INTO space_asset (id, space_id, owner_id, media_asset_id, source, kind, title, "
                + "title_base, title_version, bucket, object_key, mime_type, bytes, run_id, run_step_id, "
                + "workflow_id, workflow_name, created_at) "
                + "VALUES (:id, :space, :owner, :media, :source, :kind, :title, :base, :version, :bucket, :key, "
                + ":mime, :bytes, :run, :step, :workflow, :workflowName, :now) ON CONFLICT DO NOTHING",
                new MapSqlParameterSource("id", asset.id())
                        .addValue("space", asset.spaceId())
                        .addValue("owner", asset.ownerId())
                        .addValue("media", asset.mediaAssetId())
                        .addValue("source", asset.source())
                        .addValue("kind", asset.kind())
                        .addValue("title", asset.title())
                        .addValue("base", asset.titleBase())
                        .addValue("version", asset.titleVersion())
                        .addValue("bucket", asset.bucket())
                        .addValue("key", asset.objectKey())
                        .addValue("mime", asset.mimeType())
                        .addValue("bytes", asset.bytes())
                        .addValue("run", asset.runId())
                        .addValue("step", asset.runStepId())
                        .addValue("workflow", asset.workflowId())
                        .addValue("workflowName", asset.workflowName())
                        .addValue("now", Timestamp.from(clock.instant()))) == 1;
    }

    /** Whether the stored file already is a library asset. */
    public boolean existsByMediaAssetId(UUID mediaAssetId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM space_asset WHERE media_asset_id = :id)",
                new MapSqlParameterSource("id", mediaAssetId), Boolean.class));
    }

    /** The title version of an output asset this run already wrote, or empty when it wrote none. */
    public OptionalInt runTitleVersion(UUID runId) {
        List<Integer> versions = jdbc.queryForList(
                "SELECT title_version FROM space_asset WHERE run_id = :run AND source = 'run' LIMIT 1",
                new MapSqlParameterSource("run", runId), Integer.class);
        return versions.isEmpty() ? OptionalInt.empty() : OptionalInt.of(versions.get(0));
    }

    /** The highest title version among the space's output assets with this base, ignoring one run; 0 when none. */
    public int maxRunTitleVersion(UUID spaceId, String titleBase, UUID excludingRunId) {
        Integer max = jdbc.queryForObject("SELECT COALESCE(MAX(title_version), 0) FROM space_asset "
                + "WHERE source = 'run' AND space_id = :space AND title_base = :base AND run_id <> :run",
                new MapSqlParameterSource("space", spaceId)
                        .addValue("base", titleBase)
                        .addValue("run", excludingRunId),
                Integer.class);
        return max == null ? 0 : max;
    }
}
