package com.flowcortex.user.phone;

import com.flowcortex.shared.security.authz.Permission;
import com.flowcortex.shared.security.authz.RequirePermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/admin/sms-outbox} — the development SMS outbox, newest first, for a caller
 * holding {@code platform.sms_outbox.view}. The admin filter chain and the admin kill switch both
 * cover this path; the key is read from the database on each request. Mapped only with
 * {@code flowcortex.sms.sender=outbox}, like the sender that fills the table.
 */
@RestController
@ConditionalOnProperty(name = "flowcortex.sms.sender", havingValue = "outbox")
@RequestMapping("/api/v1/admin/sms-outbox")
class AdminSmsOutboxController {

    private static final int MAX_LIMIT = 200;

    private final SmsOutboxRepository outbox;

    AdminSmsOutboxController(SmsOutboxRepository outbox) {
        this.outbox = outbox;
    }

    @Operation(operationId = "listAdminSmsOutbox")
    @RequirePermission(Permission.PLATFORM_SMS_OUTBOX_VIEW)
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<List<SmsOutboxEntryResponse>> list(
            @RequestParam(defaultValue = "50") @Min(1) @Max(MAX_LIMIT) int limit) {
        List<SmsOutboxEntryResponse> entries = outbox.findAllByOrderByCreatedAtDesc(PageRequest.of(0, limit)).stream()
                .map(SmsOutboxEntryResponse::from)
                .toList();
        return ResponseEntity.ok(entries);
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
