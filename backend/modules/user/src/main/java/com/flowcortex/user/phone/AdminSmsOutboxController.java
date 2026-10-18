package com.flowcortex.user.phone;

import com.flowcortex.user.api.UserDirectory;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/admin/sms-outbox} — the development SMS outbox, newest first, for an ADMIN. The
 * admin filter chain and the admin kill switch both cover this path; the role is checked again
 * against the user's row, so an admin demoted since the token was issued is refused at once.
 */
@RestController
@RequestMapping("/api/v1/admin/sms-outbox")
@PreAuthorize("hasRole('ADMIN')")
class AdminSmsOutboxController {

    private static final int MAX_LIMIT = 200;

    private final SmsOutboxRepository outbox;
    private final UserDirectory userDirectory;

    AdminSmsOutboxController(SmsOutboxRepository outbox, UserDirectory userDirectory) {
        this.outbox = outbox;
        this.userDirectory = userDirectory;
    }

    @Operation(operationId = "listAdminSmsOutbox")
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<List<SmsOutboxEntryResponse>> list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "50") @Min(1) @Max(MAX_LIMIT) int limit) {
        requireAdmin(jwt);
        List<SmsOutboxEntryResponse> entries = outbox.findAllByOrderByCreatedAtDesc(PageRequest.of(0, limit)).stream()
                .map(SmsOutboxEntryResponse::from)
                .toList();
        return ResponseEntity.ok(entries);
    }

    private void requireAdmin(Jwt jwt) {
        UUID id = UUID.fromString(jwt.getSubject());
        boolean admin = userDirectory.findById(id).map(user -> "ADMIN".equals(user.role())).orElse(false);
        if (!admin) {
            throw new AccessDeniedException("User " + id + " is not an ADMIN");
        }
    }

    record SmsOutboxEntryResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Recipient in E.164") String phone,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String body,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant createdAt) {

        static SmsOutboxEntryResponse from(SmsOutboxEntry entry) {
            return new SmsOutboxEntryResponse(entry.getId(), entry.getPhoneE164(), entry.getBody(), entry.getCreatedAt());
        }
    }
}
