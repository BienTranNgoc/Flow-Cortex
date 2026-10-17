package com.flowcortex.user.application;

import com.flowcortex.user.infrastructure.AppUserRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists the failed-login counter and lockout window. Each call commits in its own transaction:
 * a failed login ends in an exception, and a counter written inside the login's transaction would
 * be rolled back with it, so the lockout would never trigger.
 */
@Component
class LoginAttemptRecorder {

    static final int MAX_FAILED_LOGIN_ATTEMPTS = 5;

    static final Duration LOCKOUT_DURATION = Duration.ofMinutes(15);

    private final AppUserRepository userRepository;
    private final Clock clock;

    LoginAttemptRecorder(AppUserRepository userRepository, Clock clock) {
        this.userRepository = userRepository;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void recordFailure(UUID userId) {
        userRepository.findByIdForUpdate(userId)
                .ifPresent(user -> user.recordFailedLogin(MAX_FAILED_LOGIN_ATTEMPTS, LOCKOUT_DURATION,
                        Instant.now(clock)));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void recordSuccess(UUID userId) {
        userRepository.findByIdForUpdate(userId).ifPresent(user -> user.resetFailedLoginAttempts(Instant.now(clock)));
    }
}
