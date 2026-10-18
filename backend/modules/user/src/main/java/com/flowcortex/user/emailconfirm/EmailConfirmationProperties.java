package com.flowcortex.user.emailconfirm;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Email confirmation of self-registered accounts, bound from {@code flowcortex.auth.email-confirmation.*}.
 * Off: register confirms the account at once and signs the member in. {@code from} is the sender
 * address of the confirmation mail. An account still unconfirmed {@code unconfirmed-account-retention}
 * after it was created, with no link left that could confirm it, is deleted, {@code purge-batch-size}
 * accounts per transaction. {@code password-setup-validity} bounds both the emailed set-password link
 * and the token a password-setup form must present.
 */
@Validated
@ConfigurationProperties("flowcortex.auth.email-confirmation")
public record EmailConfirmationProperties(
        boolean enabled,
        @NotNull @DefaultValue("24h") Duration validity,
        @NotBlank @DefaultValue("no-reply@flow-cortex.dev") String from,
        @NotNull @DefaultValue("72h") Duration unconfirmedAccountRetention,
        @NotNull @DefaultValue("30m") Duration passwordSetupValidity,
        @DefaultValue("500") int purgeBatchSize) {

    static final int MAX_PURGE_BATCH_SIZE = 10_000;

    public EmailConfirmationProperties {
        requirePositive("validity", validity);
        requirePositive("unconfirmed-account-retention", unconfirmedAccountRetention);
        requirePositive("password-setup-validity", passwordSetupValidity);
        // The batch's ids are bound one parameter each, and PostgreSQL's driver takes at most 32767.
        if (purgeBatchSize < 1 || purgeBatchSize > MAX_PURGE_BATCH_SIZE) {
            throw new IllegalArgumentException("flowcortex.auth.email-confirmation.purge-batch-size must be"
                    + " between 1 and " + MAX_PURGE_BATCH_SIZE + ", was " + purgeBatchSize);
        }
    }

    private static void requirePositive(String name, Duration value) {
        if (value != null && (value.isZero() || value.isNegative())) {
            throw new IllegalArgumentException(
                    "flowcortex.auth.email-confirmation." + name + " must be positive, was " + value);
        }
    }
}
