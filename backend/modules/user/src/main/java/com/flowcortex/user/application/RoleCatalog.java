package com.flowcortex.user.application;

import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.LocalizedStatusException;
import com.flowcortex.user.infrastructure.RoleRepository;
import com.flowcortex.user.infrastructure.RoleRow;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Reads the {@code role} table for the rules that depend on a role's flags or name; nothing is cached. */
@Component
public class RoleCatalog {

    private final RoleRepository roles;

    RoleCatalog(RoleRepository roles) {
        this.roles = roles;
    }

    public Optional<RoleRow> find(String code) {
        return code == null ? Optional.empty() : roles.findByCode(code);
    }

    /** 400 {@code role.unknown} when no row has this code. */
    public RoleRow require(String code) {
        return find(code).orElseThrow(() -> unknown(code));
    }

    /** Every staff and super-admin role: the roles whose holders appear in admin accounts. */
    public List<RoleRow> adminRoles() {
        return roles.findAll().stream().filter(RoleRow::isAdminAccount).toList();
    }

    /** Current display name by role code. */
    public Map<String, String> names() {
        return roles.namesByCode();
    }

    public static LocalizedStatusException unknown(String code) {
        return new LocalizedStatusException(HttpStatus.BAD_REQUEST, new LocalizedMessage("role.unknown", code));
    }
}
