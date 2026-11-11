package com.flowcortex.user.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** The one owner of SQL against the {@code role} table. */
@Repository
public class RoleRepository {

    private static final String COLUMNS = "code, name, system, staff, super_admin";
    private static final String DETAIL_COLUMNS =
            "code, name, description, system, staff, super_admin, permissions_version";
    private static final int CUSTOM_SORT_ORDER = 1000;

    private final JdbcTemplate jdbc;

    RoleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<RoleRow> findByCode(String code) {
        return jdbc.query("SELECT " + COLUMNS + " FROM role WHERE code = ?", (rs, rowNum) -> map(rs), code)
                .stream().findFirst();
    }

    public List<RoleRow> findAll() {
        return jdbc.query("SELECT " + COLUMNS + " FROM role ORDER BY sort_order, code", (rs, rowNum) -> map(rs));
    }

    /** Current display name by role code. */
    public Map<String, String> namesByCode() {
        Map<String, String> names = new LinkedHashMap<>();
        jdbc.query("SELECT code, name FROM role", rs -> {
            names.put(rs.getString("code"), rs.getString("name"));
        });
        return names;
    }

    public Optional<RoleDetailRow> findDetail(String code) {
        return jdbc.query("SELECT " + DETAIL_COLUMNS + " FROM role WHERE code = ?",
                (rs, rowNum) -> mapDetail(rs), code).stream().findFirst();
    }

    /** Every role in display order: super admin, system staff, custom staff by name, then end-user roles. */
    public List<RoleDetailRow> findAllDetails() {
        return jdbc.query("SELECT " + DETAIL_COLUMNS + " FROM role "
                + "ORDER BY super_admin DESC, staff DESC, system DESC, sort_order, lower(name), code",
                (rs, rowNum) -> mapDetail(rs));
    }

    public boolean exists(String code) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM role WHERE code = ?)", Boolean.class, code));
    }

    /** Accounts per role code; a role nobody holds is absent. */
    public Map<String, Integer> holderCounts() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        jdbc.query("SELECT role, count(*) AS holders FROM app_user GROUP BY role", rs -> {
            counts.put(rs.getString("role"), rs.getInt("holders"));
        });
        return counts;
    }

    public int holderCount(String code) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM app_user WHERE role = ?", Integer.class, code);
        return count == null ? 0 : count;
    }

    /** Keys held per role code, from {@code role_permission}. */
    public Map<String, List<String>> keysByRole() {
        Map<String, List<String>> keys = new LinkedHashMap<>();
        jdbc.query("SELECT role_code, permission_key FROM role_permission ORDER BY role_code, permission_key", rs -> {
            keys.computeIfAbsent(rs.getString("role_code"), k -> new ArrayList<>()).add(rs.getString("permission_key"));
        });
        return keys;
    }

    /**
     * Inserts a custom staff role unless the code is taken; the code is the arbiter, so a concurrent insert of the
     * same code never raises and never aborts the transaction. A taken name still raises on {@code uq_role_name_ci}.
     *
     * @return the code, or empty when the code was already present
     */
    public Optional<String> insertCustomIfAbsent(String code, String name, String description) {
        return jdbc.query("INSERT INTO role (code, name, description, staff, super_admin, system, sort_order) "
                + "VALUES (?, ?, ?, true, false, false, " + CUSTOM_SORT_ORDER + ") "
                + "ON CONFLICT (code) DO NOTHING RETURNING code",
                (rs, rowNum) -> rs.getString("code"), code, name, description).stream().findFirst();
    }

    public void updateText(String code, String name, String description) {
        jdbc.update("UPDATE role SET name = ?, description = ?, updated_at = now() WHERE code = ?",
                name, description, code);
    }

    /** Removes the role and, by cascade, its keys. */
    public int delete(String code) {
        return jdbc.update("DELETE FROM role WHERE code = ?", code);
    }

    /**
     * Moves the keys version up by one when it still equals {@code expectedVersion}; the row lock makes a second
     * editor wait and then match nothing.
     *
     * @return the new version, or empty when the stored version differs
     */
    public Optional<Integer> bumpVersion(String code, int expectedVersion) {
        return jdbc.query("UPDATE role SET permissions_version = permissions_version + 1, updated_at = now() "
                + "WHERE code = ? AND permissions_version = ? RETURNING permissions_version",
                (rs, rowNum) -> rs.getInt(1), code, expectedVersion).stream().findFirst();
    }

    public void replaceKeys(String code, Collection<String> keys) {
        jdbc.update("DELETE FROM role_permission WHERE role_code = ?", code);
        jdbc.batchUpdate("INSERT INTO role_permission (role_code, permission_key) VALUES (?, ?)",
                keys.stream().sorted().map(key -> new Object[] {code, key}).toList());
    }

    private static RoleDetailRow mapDetail(ResultSet rs) throws SQLException {
        return new RoleDetailRow(rs.getString("code"), rs.getString("name"), rs.getString("description"),
                rs.getBoolean("system"), rs.getBoolean("staff"), rs.getBoolean("super_admin"),
                rs.getInt("permissions_version"));
    }

    private static RoleRow map(ResultSet rs) throws SQLException {
        return new RoleRow(rs.getString("code"), rs.getString("name"), rs.getBoolean("system"),
                rs.getBoolean("staff"), rs.getBoolean("super_admin"));
    }
}
