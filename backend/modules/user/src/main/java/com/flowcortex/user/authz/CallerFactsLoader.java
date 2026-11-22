package com.flowcortex.user.authz;

import com.flowcortex.shared.security.authz.Permission;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.user.application.AccountStatusGuard;
import com.flowcortex.user.domain.Role;
import com.flowcortex.user.domain.UserStatus;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads the facts the admin decision needs: the caller's row joined to its role's flags in one
 * statement and, for an active staff role, the role's keys. A super admin or an end user costs one
 * statement, staff two.
 */
@Component
public class CallerFactsLoader {

    private static final String CALLER_ROW = """
            SELECT u.role, u.status, u.admin_disabled_at, r.staff, r.super_admin
            FROM app_user u JOIN role r ON r.code = u.role
            WHERE u.id = ?""";

    private record CallerRow(String role, UserStatus status, boolean adminDisabledSet, boolean staff,
            boolean superAdmin) {
    }

    private final JdbcTemplate jdbc;
    private final RolePermissions rolePermissions;

    CallerFactsLoader(JdbcTemplate jdbc, RolePermissions rolePermissions) {
        this.jdbc = jdbc;
        this.rolePermissions = rolePermissions;
    }

    /** 404 {@code user.current.notFound} when the row is gone; 403 for a suspended account. */
    @Transactional(readOnly = true)
    public CallerFacts load(UUID userId) {
        CallerRow row = jdbc.query(CALLER_ROW, (rs, rowNum) -> new CallerRow(rs.getString("role"),
                        UserStatus.valueOf(rs.getString("status")), rs.getObject("admin_disabled_at") != null,
                        rs.getBoolean("staff"), rs.getBoolean("super_admin")), userId)
                .stream().findFirst()
                .orElseThrow(() -> new NotFoundException(new LocalizedMessage("user.current.notFound", userId)));
        AccountStatusGuard.requireActive(row.status());
        boolean adminDisabled = row.adminDisabledSet() && (row.superAdmin() || row.staff());
        Set<Permission> keys = row.staff() && !adminDisabled
                ? rolePermissions.keysOf(Role.of(row.role())) : Set.of();
        return new CallerFacts(row.superAdmin(), adminDisabled, keys);
    }
}
