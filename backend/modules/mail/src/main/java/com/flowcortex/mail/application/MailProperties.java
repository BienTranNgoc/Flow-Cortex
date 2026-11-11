package com.flowcortex.mail.application;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The one sender every mail comes from, the secret that signs unsubscribe links, and the limit on the
 * public email-link endpoints.
 *
 * @param linkSecret HMAC key for unsubscribe links; only required once a care or promotional key exists
 */
@Validated
@ConfigurationProperties("flowcortex.mail")
public record MailProperties(
        @NotBlank @Email @DefaultValue("no-reply@flow-cortex.dev") String from,
        @NotBlank @DefaultValue("Nodi") String fromName,
        @DefaultValue("") String linkSecret,
        @Valid @DefaultValue Links links) {

    public MailProperties {
        linkSecret = linkSecret == null ? "" : linkSecret;
        links = links == null ? new Links(null) : links;
    }

    /** Settings of the public email-link endpoints. */
    public record Links(@Valid @DefaultValue RateLimit rateLimit) {

        public Links {
            rateLimit = rateLimit == null ? new RateLimit(10, Duration.ofSeconds(60)) : rateLimit;
        }
    }

    /** Calls admitted per token and address in each window. */
    public record RateLimit(@Positive @DefaultValue("10") int capacity, @NotNull @DefaultValue("60s") Duration period) {
    }
}
