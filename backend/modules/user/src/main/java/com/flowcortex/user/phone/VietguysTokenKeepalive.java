package com.flowcortex.user.phone;

import com.flowcortex.jobs.api.ClusterJob;
import java.time.Duration;

/**
 * Renews the VietGuys access token when it is due even while nobody sends, so the refresh token
 * never sits unused to the end of its life. A revoked or unseeded credential is left alone; any
 * failure to renew is thrown, so the runner records the run as failed.
 */
final class VietguysTokenKeepalive implements ClusterJob {

    private static final Duration INTERVAL = Duration.ofHours(6);

    private final VietguysTokenStore tokens;

    VietguysTokenKeepalive(VietguysTokenStore tokens) {
        this.tokens = tokens;
    }

    @Override
    public String name() {
        return "user.vietguys-token-keepalive";
    }

    @Override
    public Duration interval() {
        return INTERVAL;
    }

    @Override
    public void run() {
        if (VietguysTokenStore.ACTIVE.equals(tokens.credentialState())) {
            tokens.accessToken();
        }
    }
}
