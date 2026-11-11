package com.flowcortex.user.phone;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PhoneGateExemptionsTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/auth/me",
            "/api/v1/auth/oauth/google/callback",
            "/api/v1/me/phone",
            "/api/v1/me/phone/change/start",
            "/api/v1/me/password",
            "/api/v1/features",
            "/api/v1/support/kb/articles",
            "/api/v1/client-errors"})
    void theRoutesAHeldMemberNeedsAreExempt(String path) {
        assertThat(PhoneGateExemptions.isExempt(path)).as(path).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/workflows",
            "/api/v1/me/phonebook",
            "/api/v1/featuresx",
            "/api/v1/admin/support/kb",
            "/api/v1/me"})
    void everyOtherRouteIsNotExempt(String path) {
        assertThat(PhoneGateExemptions.isExempt(path)).as(path).isFalse();
    }
}
