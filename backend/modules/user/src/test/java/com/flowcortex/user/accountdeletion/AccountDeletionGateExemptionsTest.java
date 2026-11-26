package com.flowcortex.user.accountdeletion;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AccountDeletionGateExemptionsTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/auth/me",
            "/api/v1/auth/logout",
            "/api/v1/auth/login",
            "/api/v1/auth/register",
            "/api/v1/auth/refresh",
            "/api/v1/auth/confirm-email",
            "/api/v1/auth/resend-confirmation",
            "/api/v1/auth/forgot-password",
            "/api/v1/auth/reset-password",
            "/api/v1/auth/oauth/google/authorize",
            "/api/v1/me/account-deletion",
            "/api/v1/me/account-deletion/cancel",
            "/api/v1/me/account-deletion/eligibility",
            "/api/v1/features",
            "/api/v1/client-errors"})
    void theRoutesAPendingAccountNeedsAreExempt(String path) {
        assertThat(AccountDeletionGateExemptions.isExempt(path)).as(path).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/auth/profile",
            "/api/v1/me/step-up",
            "/api/v1/workflows",
            "/api/v1/me/account-deletionx",
            "/api/v1/me/phone",
            "/api/v1/chat/conversations",
            "/api/v1/support/requests"})
    void everyOtherRouteIsNotExempt(String path) {
        assertThat(AccountDeletionGateExemptions.isExempt(path)).as(path).isFalse();
    }
}
