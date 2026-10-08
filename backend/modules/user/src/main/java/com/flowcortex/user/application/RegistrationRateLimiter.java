package com.flowcortex.user.application;

/** Admission control for self-registration attempts, per client IP and per email. */
public interface RegistrationRateLimiter {

    /**
     * Spends one attempt from the client IP's budget, then one from the email's; the email's budget
     * is untouched when the IP is already over its limit.
     * @throws RegistrationRateLimitedException if either budget is spent
     */
    void acquire(String clientIp, String email);
}
