package com.flowcortex.billing.profile;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Every statement is filtered by the owner, so a caller can never reach another member's row. */
@Repository
class BillingProfileRepository {

    private static final String COLUMNS = "id, type, legal_name, tax_code, personal_id_number, address, email,"
            + " is_default, created_at, updated_at";

    private static final RowMapper<BillingProfile> MAPPER = (rs, row) -> new BillingProfile(
            rs.getObject("id", UUID.class), rs.getString("type"), rs.getString("legal_name"),
            rs.getString("tax_code"), rs.getString("personal_id_number"), rs.getString("address"),
            rs.getString("email"), rs.getBoolean("is_default"), rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant());

    private final NamedParameterJdbcTemplate jdbc;

    BillingProfileRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Serialises this member's writes that move the default until the transaction ends. */
    void lock(UUID user) {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtext(:key))",
                new MapSqlParameterSource("key", "billing_profile:" + user), Object.class);
    }

    List<BillingProfile> list(UUID user) {
        return jdbc.query("SELECT " + COLUMNS + " FROM billing_profile WHERE user_id = :user"
                + " ORDER BY is_default DESC, created_at, id", new MapSqlParameterSource("user", user), MAPPER);
    }

    Optional<BillingProfile> find(UUID user, UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM billing_profile WHERE user_id = :user AND id = :id",
                new MapSqlParameterSource("user", user).addValue("id", id), MAPPER).stream().findFirst();
    }

    Optional<BillingProfile> findDefault(UUID user) {
        return jdbc.query("SELECT " + COLUMNS + " FROM billing_profile WHERE user_id = :user AND is_default",
                new MapSqlParameterSource("user", user), MAPPER).stream().findFirst();
    }

    int count(UUID user) {
        return jdbc.queryForObject("SELECT count(*) FROM billing_profile WHERE user_id = :user",
                new MapSqlParameterSource("user", user), Integer.class);
    }

    void insert(UUID user, UUID id, ProfileFields fields, Instant now) {
        jdbc.update("""
                INSERT INTO billing_profile (id, user_id, type, legal_name, tax_code, personal_id_number, address, email,
                    is_default, created_at, updated_at)
                VALUES (:id, :user, :type, :legalName, :taxCode, :personalId, :address, :email, false, :now, :now)
                """, values(user, id, fields, now));
    }

    int update(UUID user, UUID id, ProfileFields fields, Instant now) {
        return jdbc.update("""
                UPDATE billing_profile SET type = :type, legal_name = :legalName, tax_code = :taxCode,
                    personal_id_number = :personalId, address = :address, email = :email, updated_at = :now
                WHERE user_id = :user AND id = :id
                """, values(user, id, fields, now));
    }

    /** Clears the member's default flag; run before {@link #markDefault} so the unique index never sees two. */
    void clearDefault(UUID user, Instant now) {
        jdbc.update("UPDATE billing_profile SET is_default = false, updated_at = :now"
                + " WHERE user_id = :user AND is_default",
                new MapSqlParameterSource("user", user).addValue("now", Timestamp.from(now)));
    }

    int markDefault(UUID user, UUID id, Instant now) {
        return jdbc.update("UPDATE billing_profile SET is_default = true, updated_at = :now"
                + " WHERE user_id = :user AND id = :id",
                new MapSqlParameterSource("user", user).addValue("id", id).addValue("now", Timestamp.from(now)));
    }

    int delete(UUID user, UUID id) {
        return jdbc.update("DELETE FROM billing_profile WHERE user_id = :user AND id = :id",
                new MapSqlParameterSource("user", user).addValue("id", id));
    }

    Optional<UUID> oldestId(UUID user) {
        return jdbc.query("SELECT id FROM billing_profile WHERE user_id = :user ORDER BY created_at, id LIMIT 1",
                new MapSqlParameterSource("user", user), (rs, row) -> rs.getObject("id", UUID.class))
                .stream().findFirst();
    }

    private static MapSqlParameterSource values(UUID user, UUID id, ProfileFields fields, Instant now) {
        return new MapSqlParameterSource("user", user)
                .addValue("id", id)
                .addValue("type", fields.type())
                .addValue("legalName", fields.legalName())
                .addValue("taxCode", fields.taxCode())
                .addValue("personalId", fields.personalIdNumber())
                .addValue("address", fields.address())
                .addValue("email", fields.email())
                .addValue("now", Timestamp.from(now));
    }
}
