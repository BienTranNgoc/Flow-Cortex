package com.flowcortex.user.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Access-token JWT properties: the claims are issuer, subject and role; only the lifetime is configurable.
 */
@ConfigurationProperties("flowcortex.security.jwt")
public record AccessTokenProperties(Duration accessTokenTtl) {

    public AccessTokenProperties {
        if (accessTokenTtl == null) {
            accessTokenTtl = Duration.ofMinutes(15);
        }
    }
}
