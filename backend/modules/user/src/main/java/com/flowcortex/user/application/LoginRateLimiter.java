package com.flowcortex.user.application;

/** Admission control for password logins: failed attempts spend a per-client-IP budget. */
public interface LoginRateLimiter {

    /**
     * @throws LoginRateLimitedException if the client IP has already spent its failed-attempt budget
     */
    void requireWithinLimit(String clientIp);

    /** Spends one attempt from the client IP's budget. */
    void recordFailure(String clientIp);
}
