package com.flowcortex.user.application;

/** Admission control for forgot-password requests, per client IP and per email. */
public interface PasswordResetRateLimiter {

    /**
     * Spends one request from the client IP's budget, then one from the email's; the email's budget
     * is untouched when the IP is already over its limit.
     * @throws PasswordResetRateLimitedException if either budget is spent
     */
    void acquire(String clientIp, String email);
}
