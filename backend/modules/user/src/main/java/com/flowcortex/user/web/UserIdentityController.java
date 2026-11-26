package com.flowcortex.user.web;

import com.flowcortex.shared.security.authz.Permission;
import com.flowcortex.shared.security.authz.RequirePermission;
import com.flowcortex.user.api.UserService;
import com.flowcortex.user.api.UserView;
import com.flowcortex.user.audit.AuditReadRecorder;
import com.flowcortex.user.pii.PiiMask;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/users/{id}/identity} — one member's email and name unmasked. The audit row is
 * written before the body is built, so a failed insert answers 503 and releases nothing. The member
 * is loaded first, so an account outside the caller's scope is a 404 that leaves no row.
 */
@RestController
@RequestMapping("/api/v1/users")
class UserIdentityController {

    static final String REVEAL_ACTION = "user.reveal_identity";

    private final UserService userService;
    private final AuditReadRecorder recorder;

    UserIdentityController(UserService userService, AuditReadRecorder recorder) {
        this.userService = userService;
        this.recorder = recorder;
    }

    @Operation(operationId = "revealUserIdentity")
    @RequirePermission({Permission.USER_ACCOUNT_VIEW, Permission.USER_ACCOUNT_VIEW_PII})
    @GetMapping(value = "/{id}/identity", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<IdentityResponse> reveal(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            HttpServletRequest request) {
        UUID callerId = UUID.fromString(jwt.getSubject());
        UserView view = userService.getUser(callerId, id);
        recorder.recordOrRefuse(callerId, REVEAL_ACTION, "user", id.toString(), PiiMask.name(view.name()), null,
                request.getRemoteAddr(), request.getHeader("User-Agent"));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new IdentityResponse(view.id(), view.email(), view.name()));
    }

    record IdentityResponse(UUID id, String email, String name) {
    }
}
