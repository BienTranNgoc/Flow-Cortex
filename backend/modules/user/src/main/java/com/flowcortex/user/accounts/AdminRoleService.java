package com.flowcortex.user.accounts;

import com.flowcortex.shared.security.authz.Permission;
import com.flowcortex.user.authz.RolePermissions;
import com.flowcortex.user.domain.Role;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads the roles and their keys for the admin console. */
@Service
class AdminRoleService {

    private static final Logger LOG = LoggerFactory.getLogger(AdminRoleService.class);

    private record RoleRow(String code, boolean staff, boolean superAdmin) {
    }

    private final JdbcTemplate jdbc;
    private final RolePermissions rolePermissions;

    AdminRoleService(JdbcTemplate jdbc, RolePermissions rolePermissions) {
        this.jdbc = jdbc;
        this.rolePermissions = rolePermissions;
    }

    /** Every role in its display order ({@code role.sort_order}); a super admin holds every key by its flag. */
    @Transactional(readOnly = true)
    List<RoleResponse> listRoles() {
        List<RoleRow> rows = jdbc.query("SELECT code, staff, super_admin FROM role ORDER BY sort_order",
                (rs, rowNum) -> new RoleRow(rs.getString("code"), rs.getBoolean("staff"),
                        rs.getBoolean("super_admin")));
        List<RoleResponse> roles = new ArrayList<>();
        for (RoleRow row : rows) {
            Role role;
            try {
                role = Role.valueOf(row.code());
            } catch (IllegalArgumentException e) {
                LOG.warn("The role table holds the code '{}' that the application does not know; skipping it",
                        row.code());
                continue;
            }
            List<Permission> keys = row.superAdmin()
                    ? Arrays.asList(Permission.values())
                    : new ArrayList<>(rolePermissions.keysOf(role));
            roles.add(new RoleResponse(row.code(), row.staff(), row.superAdmin(),
                    keys.stream().sorted(Comparator.comparing(Permission::key)).toList()));
        }
        return roles;
    }
}
