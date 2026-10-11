package com.flowcortex.user.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Access-token JWT properties. The access token remains the same shape as B1
 * (issuer, subject, role claim) with the new {@code tv} claim added.
 */
@ConfigurationProperties("flowcortex.security.jwt")
public record AccessTokenProperties(Duration accessTokenTtl) {

    public AccessTokenProperties {
        if (accessTokenTtl == null) {
            accessTokenTtl = Duration.ofMinutes(15);
        }
    }
}
