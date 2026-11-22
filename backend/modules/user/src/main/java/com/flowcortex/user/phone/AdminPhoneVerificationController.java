package com.flowcortex.user.phone;

import com.flowcortex.shared.security.authz.Permission;
import com.flowcortex.shared.security.authz.RequirePermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Clock;
import java.time.Duration;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/admin/phone-verification/status} — how the phone flow is switched and wired, for a
 * caller holding {@code platform.setting.view}. Answers flags and counts only, never a secret, hash
 * or number. The flags bind at startup, so the answer is what the running process does.
 */
@RestController
@RequestMapping("/api/v1/admin/phone-verification")
class AdminPhoneVerificationController {

    /** Sender names reported as themselves; a carrier adds its value here, anything else reads {@code custom}. */
    static final Set<String> KNOWN_SENDERS = Set.of("outbox");

    static final String NO_SENDER = "none";
    static final String CUSTOM_SENDER = "custom";

    private static final Duration SENT_WINDOW = Duration.ofHours(24);

    private final boolean verificationEnabled;
    private final boolean gateEnabled;
    private final String configuredSender;
    private final ObjectProvider<SmsSender> smsSender;
    private final PhoneNumberHasher hasher;
    private final PhoneSendLimits limits;
    private final PhoneSendAttemptStore attempts;
    private final Clock clock;

    AdminPhoneVerificationController(
            @Value("${flowcortex.phone-verification.enabled:false}") boolean verificationEnabled,
            @Value("${flowcortex.phone-gate.enabled:false}") boolean gateEnabled,
            @Value("${flowcortex.sms.sender:}") String configuredSender,
            ObjectProvider<SmsSender> smsSender,
            PhoneNumberHasher hasher,
            PhoneSendLimits limits,
            PhoneSendAttemptStore attempts,
            Clock clock) {
        this.verificationEnabled = verificationEnabled;
        this.gateEnabled = gateEnabled;
        this.configuredSender = configuredSender;
        this.smsSender = smsSender;
        this.hasher = hasher;
        this.limits = limits;
        this.attempts = attempts;
        this.clock = clock;
    }

    @Operation(operationId = "getAdminPhoneVerificationStatus")
    @RequirePermission(Permission.PLATFORM_SETTING_VIEW)
    @GetMapping(path = "/status", produces = MediaType.APPLICATION_JSON_VALUE)
    PhoneVerificationStatusResponse status() {
        return new PhoneVerificationStatusResponse(
                verificationEnabled,
                senderName(),
                hasher.configured(),
                gateEnabled,
                limits.globalSendsPerDay(),
                attempts.sentSince(clock.instant().minus(SENT_WINDOW)));
    }

    private String senderName() {
        if (smsSender.getIfAvailable() == null) {
            return NO_SENDER;
        }
        String name = configuredSender == null ? "" : configuredSender.trim();
        return KNOWN_SENDERS.contains(name) ? name : CUSTOM_SENDER;
    }

    @Schema(name = "AdminPhoneVerificationStatus")
    record PhoneVerificationStatusResponse(
            @Schema(requiredMode = RequiredMode.REQUIRED) boolean verificationEnabled,
            @Schema(requiredMode = RequiredMode.REQUIRED,
                    description = "none when no SMS sender is wired; the configured sender when it is a known one; else custom")
            String smsSender,
            @Schema(requiredMode = RequiredMode.REQUIRED,
                    description = "True when the hash secret is set and at least 32 bytes; the value is never returned")
            boolean hashSecretConfigured,
            @Schema(requiredMode = RequiredMode.REQUIRED) boolean gateEnabled,
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Cap on codes sent from every member per 24 hours")
            int globalSendsPerDay,
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Codes sent in the last 24 hours") long sentLast24h) {
    }
}
