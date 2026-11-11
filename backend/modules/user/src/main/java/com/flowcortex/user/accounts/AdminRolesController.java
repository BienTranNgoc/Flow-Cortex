package com.flowcortex.user.accounts;

import com.flowcortex.shared.security.authz.Permission;
import com.flowcortex.shared.security.authz.RequirePermission;
import com.flowcortex.user.roles.RolePermissionService;
import com.flowcortex.user.roles.RoleService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/admin/roles} and the permission catalog: reads need {@code system.role.view}, every
 * write needs {@code system.role.manage}.
 */
@RestController
@RequestMapping("/api/v1/admin")
class AdminRolesController {

    private static final int CODE_MAX = 20;
    private static final int NAME_MAX = 100;
    private static final int DESCRIPTION_MAX = 500;
    private static final int KEYS_MAX = 200;
    private static final int KEY_MAX = 100;

    private final AdminRoleService roleService;
    private final RoleService roles;
    private final RolePermissionService rolePermissions;

    AdminRolesController(AdminRoleService roleService, RoleService roles, RolePermissionService rolePermissions) {
        this.roleService = roleService;
        this.roles = roles;
        this.rolePermissions = rolePermissions;
    }

    @Operation(operationId = "listAdminRoles")
    @RequirePermission(Permission.SYSTEM_ROLE_VIEW)
    @GetMapping(value = "/roles", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<List<RoleResponse>> list() {
        return ResponseEntity.ok(roleService.listRoles());
    }

    @Operation(operationId = "getAdminRole")
    @RequirePermission(Permission.SYSTEM_ROLE_VIEW)
    @GetMapping(value = "/roles/{code}", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<RoleResponse> get(@PathVariable @Size(max = CODE_MAX) String code) {
        return ResponseEntity.ok(roleService.getRole(code));
    }

    @Operation(operationId = "listAdminPermissionCatalog")
    @RequirePermission(Permission.SYSTEM_ROLE_VIEW)
    @GetMapping(value = "/permission-catalog", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<List<PermissionCatalogEntry>> catalog() {
        return ResponseEntity.ok(roleService.catalog());
    }

    @Operation(operationId = "createAdminRole")
    @RequirePermission(Permission.SYSTEM_ROLE_MANAGE)
    @PostMapping(value = "/roles", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<RoleResponse> create(@Valid @RequestBody CreateRoleRequest request) {
        String code = roles.create(request.name(), request.description(), request.permissionKeys());
        return ResponseEntity.created(URI.create("/api/v1/admin/roles/" + code)).body(roleService.getRole(code));
    }

    @Operation(operationId = "updateAdminRole")
    @RequirePermission(Permission.SYSTEM_ROLE_MANAGE)
    @PatchMapping(value = "/roles/{code}", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<RoleResponse> update(@PathVariable @Size(max = CODE_MAX) String code,
            @Valid @RequestBody UpdateRoleRequest request) {
        roles.update(code, request.name(), request.description());
        return ResponseEntity.ok(roleService.getRole(code));
    }

    @Operation(operationId = "deleteAdminRole")
    @RequirePermission(Permission.SYSTEM_ROLE_MANAGE)
    @DeleteMapping("/roles/{code}")
    ResponseEntity<Void> delete(@PathVariable @Size(max = CODE_MAX) String code) {
        roles.delete(code);
        return ResponseEntity.noContent().build();
    }

    @Operation(operationId = "replaceAdminRolePermissions")
    @RequirePermission(Permission.SYSTEM_ROLE_MANAGE)
    @PutMapping(value = "/roles/{code}/permissions", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<RoleResponse> replacePermissions(@PathVariable @Size(max = CODE_MAX) String code,
            @Valid @RequestBody ReplacePermissionsRequest request) {
        rolePermissions.replace(code, request.keys(), request.expectedVersion());
        return ResponseEntity.ok(roleService.getRole(code));
    }

    record CreateRoleRequest(
            @NotBlank @Size(max = NAME_MAX) String name,
            @Size(max = DESCRIPTION_MAX) String description,
            @Size(max = KEYS_MAX) List<@NotNull @Size(max = KEY_MAX) String> permissionKeys) {
    }

    record UpdateRoleRequest(
            @Size(max = NAME_MAX) @Pattern(regexp = "(?s).*\\S.*") String name,
            @Size(max = DESCRIPTION_MAX) String description) {
    }

    record ReplacePermissionsRequest(
            @NotNull @Size(max = KEYS_MAX) List<@NotNull @Size(max = KEY_MAX) String> keys,
            @NotNull @Min(0) Integer expectedVersion) {
    }
}
