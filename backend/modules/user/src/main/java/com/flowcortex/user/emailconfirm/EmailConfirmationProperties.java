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
 * address of the confirmation mail.
 */
@Validated
@ConfigurationProperties("flowcortex.auth.email-confirmation")
public record EmailConfirmationProperties(
        boolean enabled,
        @NotNull @DefaultValue("24h") Duration validity,
        @NotBlank @DefaultValue("no-reply@flow-cortex.dev") String from) {

    public EmailConfirmationProperties {
        if (validity != null && (validity.isZero() || validity.isNegative())) {
            throw new IllegalArgumentException(
                    "flowcortex.auth.email-confirmation.validity must be positive, was " + validity);
        }
    }
}
