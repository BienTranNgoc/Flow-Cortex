package com.flowcortex.profile.infrastructure;

import com.flowcortex.profile.domain.ProfileImage;
import com.flowcortex.profile.domain.ProfileImage.Role;
import com.flowcortex.profile.domain.ProfileImage.Slot;
import java.sql.Timestamp;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The {@code profile_image} table. Every write for a member's slot runs inside a transaction that first
 * takes {@link #lockSlot}, so the database's one-current-per-slot index is never what resolves a race.
 */
@Repository
public class ProfileImageRepository {

    private static final String COLUMNS =
            "id, user_id, slot, role, object_key, mime_type, width, height, bytes, created_at";
    private static final RowMapper<ProfileImage> MAPPER = (rs, row) -> new ProfileImage(
            rs.getObject("id", UUID.class),
            rs.getObject("user_id", UUID.class),
            Slot.valueOf(rs.getString("slot").toUpperCase(Locale.ROOT)),
            Role.valueOf(rs.getString("role").toUpperCase(Locale.ROOT)),
            rs.getString("object_key"),
            rs.getString("mime_type"),
            rs.getInt("width"),
            rs.getInt("height"),
            rs.getLong("bytes"),
            rs.getTimestamp("created_at").toInstant());

    private final NamedParameterJdbcTemplate jdbc;

    ProfileImageRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Serialises writers of one member's slot until the surrounding transaction ends. */
    public void lockSlot(UUID userId, Slot slot) {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtext(:key))",
                new MapSqlParameterSource("key", "profile-image:" + userId + ":" + slot.wire()), Object.class);
    }

    public void insert(ProfileImage image) {
        jdbc.update("INSERT INTO profile_image (" + COLUMNS + ") VALUES (:id, :userId, :slot, :role, :objectKey, "
                + ":mimeType, :width, :height, :bytes, :createdAt)",
                new MapSqlParameterSource()
                        .addValue("id", image.id())
                        .addValue("userId", image.userId())
                        .addValue("slot", image.slot().wire())
                        .addValue("role", image.role().wire())
                        .addValue("objectKey", image.objectKey())
                        .addValue("mimeType", image.mimeType())
                        .addValue("width", image.width())
                        .addValue("height", image.height())
                        .addValue("bytes", image.bytes())
                        .addValue("createdAt", Timestamp.from(image.createdAt())));
    }

    public Optional<ProfileImage> findCurrent(UUID userId, Slot slot) {
        return jdbc.query("SELECT " + COLUMNS + " FROM profile_image WHERE user_id = :userId AND slot = :slot "
                + "AND role = 'current'", new MapSqlParameterSource("userId", userId).addValue("slot", slot.wire()),
                MAPPER).stream().findFirst();
    }

    /** The newest {@code limit} originals of the slot, newest first. */
    public List<ProfileImage> findOriginalsNewestFirst(UUID userId, Slot slot, int limit) {
        return jdbc.query("SELECT " + COLUMNS + " FROM profile_image WHERE user_id = :userId AND slot = :slot "
                + "AND role = 'original' ORDER BY created_at DESC, id DESC LIMIT :limit",
                new MapSqlParameterSource("userId", userId).addValue("slot", slot.wire()).addValue("limit", limit),
                MAPPER);
    }

    /** Removes the slot's current image and returns it, or empty when it had none. */
    public Optional<ProfileImage> deleteCurrent(UUID userId, Slot slot) {
        return jdbc.query("DELETE FROM profile_image WHERE user_id = :userId AND slot = :slot AND role = 'current' "
                + "RETURNING " + COLUMNS, new MapSqlParameterSource("userId", userId).addValue("slot", slot.wire()),
                MAPPER).stream().findFirst();
    }

    public void deleteAll(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return;
        }
        jdbc.update("DELETE FROM profile_image WHERE id IN (:ids)", new MapSqlParameterSource("ids", ids));
    }
}
