package com.flowcortex.user.authz;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AuthzInventoryTest {

    private static final Set<String> SEEDED = Set.of("user.account.view", "workflow.run.view");

    private static RouteDescriptor route(String method, String pattern, int postures, String... keys) {
        return new RouteDescriptor(method, pattern, postures, Set.of(keys));
    }

    @Test
    void aGovernedRouteWithoutAPostureIsAViolationNamingTheRoute() {
        List<String> violations = AuthzInventory.violations(
                List.of(route("GET", "/api/v1/admin/reports", 0)), SEEDED);

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0)).contains("GET /api/v1/admin/reports");
    }

    @Test
    void aRouteWithTwoPosturesIsAViolation() {
        List<String> violations = AuthzInventory.violations(
                List.of(route("PUT", "/api/v1/admin/runs", 2, "workflow.run.view")), SEEDED);

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0)).contains("PUT /api/v1/admin/runs");
    }

    @Test
    void aPostureNamingAKeyAbsentFromTheSeededSetIsAViolationNamingTheKey() {
        List<String> violations = AuthzInventory.violations(
                List.of(route("GET", "/api/v1/admin/runs", 1, "workflow.run.export")), SEEDED);

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0)).contains("workflow.run.export").contains("GET /api/v1/admin/runs");
    }

    @Test
    void anUngovernedRouteWithoutAPostureIsNotAViolation() {
        assertThat(AuthzInventory.violations(List.of(route("GET", "/api/v1/workflows", 0)), SEEDED)).isEmpty();
    }

    @Test
    void anUngovernedRouteCarryingAPostureIsStillChecked() {
        List<String> violations = AuthzInventory.violations(
                List.of(route("GET", "/api/v1/workflows", 1, "workflow.run.export")), SEEDED);

        assertThat(violations).hasSize(1);
    }

    @Test
    void theUsersPathsAreGovernedAndUsersettingsIsNot() {
        List<String> violations = AuthzInventory.violations(List.of(
                route("GET", "/api/v1/users", 0),
                route("GET", "/api/v1/users/{id}", 0),
                route("GET", "/api/v1/usersettings", 0)), SEEDED);

        assertThat(violations).hasSize(2);
        assertThat(violations.get(0)).contains("GET /api/v1/users");
        assertThat(violations.get(1)).contains("GET /api/v1/users/{id}");
    }

    @Test
    void aCorrectlyDeclaredInventoryHasNoViolation() {
        assertThat(AuthzInventory.violations(List.of(
                route("GET", "/api/v1/admin/runs", 1, "workflow.run.view"),
                route("GET", "/api/v1/users", 1, "user.account.view"),
                route("POST", "/api/v1/auth/login", 1)), SEEDED)).isEmpty();
    }
}
