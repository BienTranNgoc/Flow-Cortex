package com.flowcortex.asset.infrastructure;

import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The only writer of {@code space_asset.missing_at}. Each call is one idempotent statement, so two requests
 * that disagree about the file never need a lock: the first time found gone is kept, and a later sighting clears it.
 */
@Repository
public class AssetMissingMarks {

    private final NamedParameterJdbcTemplate jdbc;

    public AssetMissingMarks(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Marks the asset missing now; an asset already marked keeps the earlier time. */
    public void markGone(UUID id, UUID spaceId) {
        jdbc.update("UPDATE space_asset SET missing_at = COALESCE(missing_at, now()) "
                + "WHERE id = :id AND space_id = :spaceId", parameters(id, spaceId));
    }

    /** Clears the mark of an asset whose file is back; an asset that is not marked is left untouched. */
    public void clear(UUID id, UUID spaceId) {
        jdbc.update("UPDATE space_asset SET missing_at = NULL "
                + "WHERE id = :id AND space_id = :spaceId AND missing_at IS NOT NULL", parameters(id, spaceId));
    }

    private static MapSqlParameterSource parameters(UUID id, UUID spaceId) {
        return new MapSqlParameterSource().addValue("id", id).addValue("spaceId", spaceId);
    }
}
