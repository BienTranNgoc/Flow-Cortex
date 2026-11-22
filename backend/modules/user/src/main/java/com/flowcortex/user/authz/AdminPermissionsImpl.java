package com.flowcortex.user.authz;

import com.flowcortex.shared.security.authz.Permission;
import com.flowcortex.user.api.AdminAssignee;
import com.flowcortex.user.api.AdminPermissions;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class AdminPermissionsImpl implements AdminPermissions {

    private static final int MAX_HOLDERS = 500;
    /** The one eligibility predicate: both the list and the single-account check read it. */
    private static final String HOLDS = "status = 'ACTIVE' AND admin_disabled_at IS NULL AND (role = 'ADMIN' "
            + "OR role IN (SELECT role_code FROM role_permission WHERE permission_key = :key))";

    private final CallerFactsLoader factsLoader;
    private final NamedParameterJdbcTemplate jdbc;

    AdminPermissionsImpl(CallerFactsLoader factsLoader, NamedParameterJdbcTemplate jdbc) {
        this.factsLoader = factsLoader;
        this.jdbc = jdbc;
    }

    @Override
    public void require(UUID userId, Permission key) {
        AdminAuthorizationDecision.decide(new Posture.AllOf(Set.of(key)), factsLoader.load(userId)).requireAllowed();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AdminAssignee> holders(Permission key) {
        return jdbc.query("SELECT id, email, name FROM app_user WHERE " + HOLDS + " ORDER BY email LIMIT "
                + MAX_HOLDERS, new MapSqlParameterSource("key", key.key()),
                (rs, row) -> new AdminAssignee(rs.getObject("id", UUID.class), rs.getString("email"),
                        rs.getString("name")));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean holds(UUID userId, Permission key) {
        Boolean held = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM app_user WHERE id = :id AND " + HOLDS + ")",
                new MapSqlParameterSource("key", key.key()).addValue("id", userId), Boolean.class);
        return Boolean.TRUE.equals(held);
    }
}
