package com.flowcortex.user.application;

import com.flowcortex.shared.ratelimit.RateLimitDefinition;
import com.flowcortex.user.passwordreset.PasswordResetProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The user module's limits on the shared registry, each sized from its existing properties. */
@Configuration(proxyBeanMethods = false)
class UserRateLimits {

    static final String LOGIN_FAILURES_IP = "login-failures-ip";
    static final String REGISTRATION_IP = "registration-ip";
    static final String REGISTRATION_EMAIL = "registration-email";
    static final String PASSWORD_RESET_IP = "password-reset-ip";
    static final String PASSWORD_RESET_EMAIL = "password-reset-email";
    static final String PASSWORD_RESET_SUBMIT_IP = "password-reset-submit-ip";

    @Bean
    RateLimitDefinition loginFailuresIp(LoginThrottleProperties properties) {
        return definition(LOGIN_FAILURES_IP, properties.failuresPerIp());
    }

    @Bean
    RateLimitDefinition registrationIp(RegistrationProperties properties) {
        return definition(REGISTRATION_IP, properties.perIp());
    }

    @Bean
    RateLimitDefinition registrationEmail(RegistrationProperties properties) {
        return definition(REGISTRATION_EMAIL, properties.perEmail());
    }

    @Bean
    RateLimitDefinition passwordResetIp(PasswordResetProperties properties) {
        return definition(PASSWORD_RESET_IP, properties.perIp());
    }

    @Bean
    RateLimitDefinition passwordResetEmail(PasswordResetProperties properties) {
        return definition(PASSWORD_RESET_EMAIL, properties.perEmail());
    }

    @Bean
    RateLimitDefinition passwordResetSubmitIp(PasswordResetProperties properties) {
        return definition(PASSWORD_RESET_SUBMIT_IP, properties.submitPerIp());
    }

    private static RateLimitDefinition definition(String name, RateLimit limit) {
        return new RateLimitDefinition(name, limit.capacity(), limit.period());
    }
}
