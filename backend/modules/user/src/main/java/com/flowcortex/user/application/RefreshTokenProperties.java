package com.flowcortex.user.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Refresh-token TTLs and the per-user cap on active rows. Defaults match the
 * values declared in proposal §G2/G3.
 */
@ConfigurationProperties("flowcortex.security.refresh-token")
public record RefreshTokenProperties(Duration ttl, Duration rememberMeTtl, int maxActivePerUser) {

    public RefreshTokenProperties {
        if (ttl == null) {
            ttl = Duration.ofDays(7);
        }
        if (rememberMeTtl == null) {
            rememberMeTtl = Duration.ofDays(30);
        }
        if (maxActivePerUser <= 0) {
            maxActivePerUser = 10;
        }
    }
}
