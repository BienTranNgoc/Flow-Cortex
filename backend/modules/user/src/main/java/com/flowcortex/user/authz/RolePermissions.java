package com.flowcortex.user.authz;

import com.flowcortex.shared.security.authz.Permission;
import com.flowcortex.user.domain.Role;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** The keys a role holds, read from {@code role_permission} on every call so a change applies at once. */
@Component
public class RolePermissions {

    private static final Logger LOG = LoggerFactory.getLogger(RolePermissions.class);

    private final JdbcTemplate jdbc;

    RolePermissions(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Set<Permission> keysOf(Role role) {
        List<String> keys = jdbc.queryForList(
                "SELECT permission_key FROM role_permission WHERE role_code = ?", String.class, role.name());
        Set<Permission> held = EnumSet.noneOf(Permission.class);
        for (String key : keys) {
            Permission.fromKey(key).ifPresentOrElse(held::add,
                    () -> LOG.warn("role_permission holds the unknown key '{}' for role {}", key, role));
        }
        return held;
    }
}
