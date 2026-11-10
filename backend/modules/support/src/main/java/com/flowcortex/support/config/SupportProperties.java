package com.flowcortex.support.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Bound from {@code flowcortex.support.*}; every value has its default in {@code application.yml}.
 * Read once at startup, so a change needs a restart. An empty contact address is valid and means
 * no contact line.
 */
@Validated
@ConfigurationProperties("flowcortex.support")
public record SupportProperties(
        boolean enabled,
        @Email String contactEmail,
        @NotNull @Valid Assistant assistant,
        @NotNull @Valid Mail mail,
        @NotNull @Valid Lifecycle lifecycle) {

    /** The contact address, absent when none is configured. */
    public Optional<String> contact() {
        return contactEmail == null || contactEmail.isBlank() ? Optional.empty() : Optional.of(contactEmail.trim());
    }

    /**
     * The assistant's hard switch, model and daily limits: per member, per guest session, per guest
     * network, and the ceiling over all questions.
     */
    public record Assistant(
            boolean enabled,
            @NotBlank String model,
            @Min(1) int dailyCeiling,
            @Min(1) int memberDailyQuota,
            @Min(1) int guestDailyQuota,
            @Min(1) int guestNetworkDailyQuota) {
    }

    /** Sender of the reply notification mails. */
    public record Mail(@NotBlank @Email String from) {
    }

    /**
     * The configured layer of the six lifecycle settings. A switch set to false here stays off whatever an
     * admin stores; each integer is the value in force until an admin stores another.
     */
    public record Lifecycle(
            boolean autoCloseEnabled,
            @Min(1) @Max(90) int autoCloseResolvedDays,
            boolean replyMailEnabled,
            @Min(0) @Max(1440) int replyMailWindowMinutes,
            boolean retentionEnabled,
            @Min(6) @Max(120) int retentionClosedRequestMonths) {
    }
}
