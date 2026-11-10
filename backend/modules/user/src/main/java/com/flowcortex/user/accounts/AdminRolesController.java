package com.flowcortex.user.accounts;

import com.flowcortex.shared.security.authz.Permission;
import com.flowcortex.shared.security.authz.RequirePermission;
import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code /api/v1/admin/roles} — the roles and their keys, for a caller holding {@code system.role.view}. */
@RestController
@RequestMapping("/api/v1/admin/roles")
class AdminRolesController {

    private final AdminRoleService roleService;

    AdminRolesController(AdminRoleService roleService) {
        this.roleService = roleService;
    }

    @Operation(operationId = "listAdminRoles")
    @RequirePermission(Permission.SYSTEM_ROLE_VIEW)
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<List<RoleResponse>> list() {
        return ResponseEntity.ok(roleService.listRoles());
    }
}
