package com.flowcortex.user.accounts;

import com.flowcortex.shared.security.authz.Permission;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.user.infrastructure.RoleDetailRow;
import com.flowcortex.user.infrastructure.RoleRepository;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads the roles and the permission catalog for the admin console. */
@Service
class AdminRoleService {

    private static final Logger LOG = LoggerFactory.getLogger(AdminRoleService.class);

    private final RoleRepository roles;

    AdminRoleService(RoleRepository roles) {
        this.roles = roles;
    }

    /** Every role in its display order; a super admin holds every key by its flag. */
    @Transactional(readOnly = true)
    List<RoleResponse> listRoles() {
        Map<String, List<String>> keys = roles.keysByRole();
        Map<String, Integer> counts = roles.holderCounts();
        return roles.findAllDetails().stream().map(row -> toResponse(row, keys, counts)).toList();
    }

    /** One role; 404 when no row has this code. */
    @Transactional(readOnly = true)
    RoleResponse getRole(String code) {
        RoleDetailRow row = roles.findDetail(code)
                .orElseThrow(() -> new NotFoundException(new LocalizedMessage("role.notFound", code)));
        return toResponse(row, roles.keysByRole(), Map.of(code, roles.holderCount(code)));
    }

    /** The catalog in declaration order, which is the display order. */
    List<PermissionCatalogEntry> catalog() {
        return Arrays.stream(Permission.values()).map(PermissionCatalogEntry::of).toList();
    }

    private RoleResponse toResponse(RoleDetailRow row, Map<String, List<String>> keysByRole,
            Map<String, Integer> counts) {
        List<Permission> held = new ArrayList<>();
        if (row.superAdmin()) {
            held.addAll(Arrays.asList(Permission.values()));
        } else {
            for (String key : keysByRole.getOrDefault(row.code(), List.of())) {
                Permission.fromKey(key).ifPresentOrElse(held::add,
                        () -> LOG.warn("role_permission holds the unknown key '{}' for role {}", key, row.code()));
            }
        }
        held.sort(Comparator.comparing(Permission::key));
        return new RoleResponse(row.code(), row.name(), row.description(), row.system(), row.staff(),
                row.superAdmin(), List.copyOf(held), row.permissionsVersion(), counts.getOrDefault(row.code(), 0));
    }
}
