package com.flowcortex.user.pat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpMethod;

class PatRoutePolicyTest {

    @ParameterizedTest
    @CsvSource({
            "GET,/api/v1/workflows",
            "POST,/api/v1/workflows",
            "GET,/api/v1/workflows/{id}",
            "POST,/api/v1/runs/{id}/cancel",
            "GET,/api/v1/runs",
            "GET,/api/v1/review-tasks/{taskId}/revisions",
            "POST,/api/v1/uploads",
            "GET,/api/v1/product-videos/{id}",
            "GET,/api/v1/credits/balance",
            "GET,/api/v1/auth/me"})
    void allowsTheTableRows(HttpMethod method, String pattern) {
        assertThat(PatRoutePolicy.allows(method, pattern)).isTrue();
    }

    @ParameterizedTest
    @CsvSource({
            "PUT,/api/v1/auth/profile",
            "POST,/api/v1/auth/me",
            "GET,/api/v1/auth/me/extra",
            "GET,/api/v1/admin/runs",
            "GET,/api/v1/users",
            "POST,/api/v1/me/api-tokens",
            "GET,/api/v1/me/api-tokens",
            "POST,/api/v1/me/step-up",
            "PUT,/api/v1/me/password",
            "GET,/api/v1/me/phone",
            "GET,/api/v1/realtime/stream",
            "GET,/api/v1/support/kb",
            "GET,/api/v1/workflowsX",
            "GET,/api/v1/runsX/{id}",
            "GET,/api/v1/creditsX"})
    void refusesEverythingElse(HttpMethod method, String pattern) {
        assertThat(PatRoutePolicy.allows(method, pattern)).isFalse();
    }

    @Test
    void aMissingPatternOrMethodIsRefused() {
        assertThat(PatRoutePolicy.allows(HttpMethod.GET, null)).isFalse();
        assertThat(PatRoutePolicy.allows(null, "/api/v1/workflows")).isFalse();
    }
}
