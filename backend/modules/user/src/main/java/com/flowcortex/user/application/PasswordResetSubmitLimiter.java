package com.flowcortex.user.application;

/** Admission control for new-password submissions from reset links, per client IP. */
public interface PasswordResetSubmitLimiter {

    /**
     * Spends one submission from the client IP's budget, whatever the token turns out to be.
     * @throws PasswordResetRateLimitedException if the budget is spent
     */
    void acquire(String clientIp);
}
