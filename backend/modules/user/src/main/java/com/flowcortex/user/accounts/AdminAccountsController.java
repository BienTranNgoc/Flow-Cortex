package com.flowcortex.user.accounts;

import com.flowcortex.shared.security.authz.Permission;
import com.flowcortex.shared.security.authz.RequirePermission;
import com.flowcortex.shared.web.PageView;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/admin/accounts} — list, change the role of, deactivate and reactivate the accounts whose
 * role is staff or {@code ADMIN}, across every owner scope.
 */
@RestController
@RequestMapping("/api/v1/admin/accounts")
class AdminAccountsController {

    private static final int ROLE_CODE_MAX = 20;
    private static final int MAX_PAGE_SIZE = 100;
    /** Keeps {@code page * size} within an {@code int}, the offset type Spring Data pages with. */
    private static final int MAX_PAGE = Integer.MAX_VALUE / MAX_PAGE_SIZE;

    private final AdminAccountService accounts;

    AdminAccountsController(AdminAccountService accounts) {
        this.accounts = accounts;
    }

    @Operation(operationId = "listAdminAccounts")
    @RequirePermission(Permission.SYSTEM_ADMIN_ACCOUNT_VIEW)
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<PageView<AdminAccountResponse>> list(
            @RequestParam(required = false) @Size(max = ROLE_CODE_MAX) String role,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") @Min(0) @Max(MAX_PAGE) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size) {
        return ResponseEntity.ok(accounts.list(role, q, page, size));
    }

    @Operation(operationId = "changeAdminAccountRole")
    @RequirePermission(Permission.SYSTEM_ADMIN_ACCOUNT_MANAGE)
    @PatchMapping(value = "/{id}/role", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<AdminAccountResponse> changeRole(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @Valid @RequestBody ChangeRoleRequest request) {
        return ResponseEntity.ok(accounts.changeRole(callerId(jwt), id, request.role()));
    }

    @Operation(operationId = "deactivateAdminAccount")
    @RequirePermission(Permission.SYSTEM_ADMIN_ACCOUNT_MANAGE)
    @PostMapping(value = "/{id}/deactivate", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<AdminAccountResponse> deactivate(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return ResponseEntity.ok(accounts.deactivate(callerId(jwt), id));
    }

    @Operation(operationId = "activateAdminAccount")
    @RequirePermission(Permission.SYSTEM_ADMIN_ACCOUNT_MANAGE)
    @PostMapping(value = "/{id}/activate", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<AdminAccountResponse> activate(@PathVariable UUID id) {
        return ResponseEntity.ok(accounts.activate(id));
    }

    private static UUID callerId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    record ChangeRoleRequest(@NotBlank @Size(min = 1, max = ROLE_CODE_MAX) String role) {
    }
}
