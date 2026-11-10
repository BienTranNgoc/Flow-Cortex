package com.flowcortex.user.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.shared.web.audit.AdminAudit;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class AdminAuditInventoryTest {

    private record ReportBody(String passwordHint, String color, String title) {}

    private static class BeanBody {
        private String title;

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }
    }

    @SuppressWarnings("unused")
    private static class Declarations {
        @AdminAudit("report.update")
        void plain() {}

        @AdminAudit("Report.Update")
        void badAction() {}

        @AdminAudit(value = "report.update", resourceParam = "reportId")
        void badResourceParam() {}

        @AdminAudit(value = "report.create", resourceParam = "", params = "id")
        void nestedCreate() {}

        @AdminAudit(value = "report.update", params = "missing")
        void badParam() {}

        @AdminAudit(value = "report.update", capture = {"passwordHint", "colour", "title"})
        void badCaptures() {}

        @AdminAudit(value = "report.update", capture = "title")
        void goodCapture() {}
    }

    private static AdminAudit audit(String method) throws NoSuchMethodException {
        Method declared = Declarations.class.getDeclaredMethod(method);
        return declared.getAnnotation(AdminAudit.class);
    }

    private static AuditRouteDescriptor route(String method, String pattern, AdminAudit audit, Class<?> body) {
        return new AuditRouteDescriptor(
                method, List.of(pattern), AuditRouteDescriptor.pathVariablesOf(List.of(pattern)), audit, body);
    }

    @Test
    void anUnannotatedGovernedWriteRouteIsReported() {
        List<String> violations = AdminAuditInventory.violations(
                List.of(route("PUT", "/api/v1/admin/reports/{id}", null, null)));

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0)).startsWith("PUT /api/v1/admin/reports/{id}").contains("no @AdminAudit");
    }

    @Test
    void readRoutesAndMemberRoutesAreNotReported() {
        List<String> violations = AdminAuditInventory.violations(List.of(
                route("GET", "/api/v1/admin/runs", null, null),
                route("POST", "/api/v1/workflows", null, null),
                route("PUT", "/api/v1/usersettings", null, null)));

        assertThat(violations).isEmpty();
    }

    @Test
    void theUsersCollectionIsGoverned() {
        List<String> violations = AdminAuditInventory.violations(List.of(
                route("DELETE", "/api/v1/users/{id}", null, null)));

        assertThat(violations).hasSize(1);
    }

    @Test
    void anAnnotatedRouteWithAValidDeclarationPasses() throws Exception {
        List<String> violations = AdminAuditInventory.violations(List.of(
                route("PUT", "/api/v1/admin/reports/{id}", audit("goodCapture"), ReportBody.class)));

        assertThat(violations).isEmpty();
    }

    @Test
    void anActionBreakingTheGrammarIsReported() throws Exception {
        List<String> violations = AdminAuditInventory.violations(List.of(
                route("PUT", "/api/v1/admin/reports/{id}", audit("badAction"), null)));

        assertThat(violations).singleElement().asString().contains("Report.Update").contains("<resource_type>.<verb>");
    }

    @Test
    void aResourceParameterTheRouteLacksIsReported() throws Exception {
        List<String> violations = AdminAuditInventory.violations(List.of(
                route("PUT", "/api/v1/admin/reports/{id}", audit("badResourceParam"), null)));

        assertThat(violations).singleElement().asString().contains("reportId");
    }

    @Test
    void aCreateRouteWithNoPathVariableTakesItsIdFromTheResponse() throws Exception {
        List<String> violations = AdminAuditInventory.violations(List.of(
                route("POST", "/api/v1/admin/reports", audit("plain"), null)));

        assertThat(violations).isEmpty();
    }

    @Test
    void aNestedCreateRouteTakingItsIdFromTheResponseIsNotReported() throws Exception {
        List<String> violations = AdminAuditInventory.violations(List.of(
                route("POST", "/api/v1/admin/reports/{id}/items", audit("nestedCreate"), null)));

        assertThat(violations).isEmpty();
    }

    @Test
    void aDeclaredPathParameterTheRouteLacksIsReported() throws Exception {
        List<String> violations = AdminAuditInventory.violations(List.of(
                route("PUT", "/api/v1/admin/reports/{id}", audit("badParam"), null)));

        assertThat(violations).singleElement().asString().contains("missing");
    }

    @Test
    void unknownAndSecretLikeCapturesAreReported() throws Exception {
        List<String> violations = AdminAuditInventory.violations(List.of(
                route("PUT", "/api/v1/admin/reports/{id}", audit("badCaptures"), ReportBody.class)));

        assertThat(violations).hasSize(2);
        assertThat(violations).anyMatch(v -> v.contains("colour") && v.contains("not a field"));
        assertThat(violations).anyMatch(v -> v.contains("passwordHint") && v.contains("secret"));
    }

    @Test
    void beanPropertiesCountAsBodyFields() throws Exception {
        List<String> violations = AdminAuditInventory.violations(List.of(
                route("PUT", "/api/v1/admin/reports/{id}", audit("goodCapture"), BeanBody.class)));

        assertThat(violations).isEmpty();
    }

    @Test
    void capturesOnAnUncheckableBodyAreReported() throws Exception {
        List<String> violations = AdminAuditInventory.violations(List.of(
                route("PUT", "/api/v1/admin/reports/{id}", audit("goodCapture"), JsonNode.class),
                route("PUT", "/api/v1/admin/reports/{id}", audit("goodCapture"), java.util.Map.class)));

        assertThat(violations).hasSize(2).allMatch(v -> v.contains("cannot be checked"));
    }

    @Test
    void capturesOnARouteWithoutABodyAreReported() throws Exception {
        List<String> violations = AdminAuditInventory.violations(List.of(
                route("PUT", "/api/v1/admin/reports/{id}", audit("goodCapture"), null)));

        assertThat(violations).singleElement().asString().contains("no request body");
    }

    @Test
    void pathVariablesAreReadFromThePatterns() {
        assertThat(AuditRouteDescriptor.pathVariablesOf(List.of("/a/{id}/steps/{stepId}", "/b/{key:[a-z]+}")))
                .isEqualTo(Set.of("id", "stepId", "key"));
    }
}
