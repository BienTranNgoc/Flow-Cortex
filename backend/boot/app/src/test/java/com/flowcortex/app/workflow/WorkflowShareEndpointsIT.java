package com.flowcortex.app.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.user.api.UserView;
import com.flowcortex.workflow.api.NewWorkflowStep;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The sharing routes over HTTP: who may call them, how a share key admits a signed-in recipient, and where the key
 * is and is not returned. The rules behind them are covered by the service tests and the repository IT.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class WorkflowShareEndpointsIT extends AbstractWorkflowHttpIT {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void onlyTheByKeyRouteAnswersAnAnonymousCaller() {
        UserView owner = newUser("share-anon-owner");
        UUID workflowId = newWorkflow(owner.id(), false, new NewWorkflowStep("only", "echo", null)).id();
        String key = makeUnlisted(workflowId, loginAs(owner));

        ResponseEntity<Map> byKey = call(HttpMethod.GET, "/api/v1/workflows/by-key/" + key, null, null);
        assertThat(byKey.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(byKey.getBody()).containsEntry("id", workflowId.toString());

        for (String path : List.of("/api/v1/workflows", "/api/v1/workflows/" + workflowId + "?key=" + key,
                "/api/v1/workflows/" + workflowId + "/sharing")) {
            assertThat(call(HttpMethod.GET, path, null, null).getStatusCode()).as(path)
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        assertThat(call(HttpMethod.GET, "/api/v1/workflows/by-key/" + key, "not-a-token", null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void aRecipientOpensTheLinkSignsInAndReturnsWithoutTheKey() {
        UserView owner = newUser("share-return-owner");
        UserView ben = member(owner, "share-return-ben");
        UUID workflowId = newWorkflow(owner.id(), false, new NewWorkflowStep("only", "echo", null)).id();
        String key = makeUnlisted(workflowId, loginAs(owner));
        String benToken = loginAs(ben);

        assertThat(call(HttpMethod.GET, "/api/v1/workflows/" + workflowId, benToken, null).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(HttpMethod.GET, "/api/v1/workflows/" + workflowId + "?key=" + key, benToken, null)
                .getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(call(HttpMethod.GET, "/api/v1/workflows/" + workflowId, benToken, null).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        ResponseEntity<Map> run = call(HttpMethod.POST, "/api/v1/workflows/" + workflowId + "/runs", benToken,
                Map.of("input", "hello"));
        assertThat(run.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(run.getBody()).containsEntry("workflowId", workflowId.toString());

        ResponseEntity<String> list = call(HttpMethod.GET, "/api/v1/workflows", benToken, null, String.class);
        assertThat(list.getBody()).doesNotContain(workflowId.toString());
    }

    @Test
    void revokingAndLeavingUnlistedEndAccess() {
        UserView owner = newUser("share-revoke-owner");
        UserView ben = member(owner, "share-revoke-ben");
        UUID workflowId = newWorkflow(owner.id(), false, new NewWorkflowStep("only", "echo", null)).id();
        String ownerToken = loginAs(owner);
        String benToken = loginAs(ben);
        String key = makeUnlisted(workflowId, ownerToken);
        String detail = "/api/v1/workflows/" + workflowId;

        call(HttpMethod.GET, detail + "?key=" + key, benToken, null);
        ResponseEntity<Map> revoked = call(HttpMethod.DELETE, detail + "/link-grants/" + ben.id(), ownerToken, null);
        assertThat(revoked.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(revoked.getBody()).containsEntry("revoked", 1);
        assertThat(call(HttpMethod.GET, detail, benToken, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        assertThat(call(HttpMethod.GET, detail + "?key=" + key, benToken, null).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.PATCH, detail + "/visibility", ownerToken, Map.of("visibility", "private"))
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.GET, detail, benToken, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        String newKey = makeUnlisted(workflowId, ownerToken);
        assertThat(newKey).isNotEqualTo(key);
        assertThat(call(HttpMethod.GET, detail, benToken, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(HttpMethod.GET, detail + "?key=" + key, benToken, null).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void theShareKeyAppearsOnlyOnTheOwnersSharingResponses() {
        UserView owner = newUser("share-leak-owner");
        UserView ben = member(owner, "share-leak-ben");
        UUID workflowId = newWorkflow(owner.id(), false, new NewWorkflowStep("only", "echo", null)).id();
        String ownerToken = loginAs(owner);
        String benToken = loginAs(ben);
        String key = makeUnlisted(workflowId, ownerToken);
        String detail = "/api/v1/workflows/" + workflowId;

        assertThat(call(HttpMethod.GET, detail + "/sharing", ownerToken, null).getBody()).containsEntry("shareKey",
                key);
        call(HttpMethod.GET, detail + "?key=" + key, benToken, null);

        for (String path : List.of("/api/v1/workflows", detail, "/api/v1/workflows/by-key/" + key)) {
            for (String token : List.of(ownerToken, benToken)) {
                ResponseEntity<String> response = call(HttpMethod.GET, path, token, null, String.class);
                assertThat(response.getStatusCode().is2xxSuccessful()).as(path).isTrue();
                assertThat(response.getBody()).as(path).doesNotContain(key);
            }
        }
        assertThat(call(HttpMethod.GET, detail + "/access-log", ownerToken, null, String.class).getBody())
                .doesNotContain(key);
        assertThat(call(HttpMethod.GET, detail + "/visibility-audit", ownerToken, null, String.class).getBody())
                .doesNotContain(key);
    }

    @Test
    void aGrantHolderWhoPatchesVisibilityGets403() {
        UserView owner = newUser("share-grant-owner");
        UserView ben = member(owner, "share-grant-ben");
        UUID workflowId = newWorkflow(owner.id(), false, new NewWorkflowStep("only", "echo", null)).id();
        String key = makeUnlisted(workflowId, loginAs(owner));
        String benToken = loginAs(ben);
        String detail = "/api/v1/workflows/" + workflowId;
        call(HttpMethod.GET, detail + "?key=" + key, benToken, null);

        ResponseEntity<Map> patched = call(HttpMethod.PATCH, detail + "/visibility", benToken,
                Map.of("visibility", "public"));

        assertThat(patched.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(String.valueOf(patched.getBody().get("detail"))).isNotBlank();
        assertThat(jdbc.queryForObject("SELECT visibility FROM workflow WHERE id = ?", String.class, workflowId))
                .isEqualTo("unlisted");
    }

    @Test
    void anUnknownTierIsRefusedNamingVisibilityAndChangesNothing() {
        UserView owner = newUser("share-tier-owner");
        UUID workflowId = newWorkflow(owner.id(), false, new NewWorkflowStep("only", "echo", null)).id();

        ResponseEntity<Map> response = call(HttpMethod.PATCH, "/api/v1/workflows/" + workflowId + "/visibility",
                loginAs(owner), Map.of("visibility", "team"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat((Map<String, Object>) response.getBody().get("errors")).containsKey("visibility");
        assertThat(jdbc.queryForObject("SELECT visibility FROM workflow WHERE id = ?", String.class, workflowId))
                .isEqualTo("private");
    }

    @Test
    void anExpiryThatIsNotAnInstantIs422NamingExpiresAtAndChangesNothing() {
        UserView owner = newUser("share-expiry-owner");
        UUID workflowId = newWorkflow(owner.id(), false, new NewWorkflowStep("only", "echo", null)).id();

        ResponseEntity<Map> response = call(HttpMethod.PATCH, "/api/v1/workflows/" + workflowId + "/visibility",
                loginAs(owner), Map.of("visibility", "unlisted", "expiresAt", "tomorrow"));

        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat((Map<String, Object>) response.getBody().get("errors")).containsKey("expiresAt");
        assertThat(jdbc.queryForObject("SELECT visibility FROM workflow WHERE id = ?", String.class, workflowId))
                .isEqualTo("private");
    }

    private String makeUnlisted(UUID workflowId, String ownerToken) {
        ResponseEntity<Map> response = call(HttpMethod.PATCH, "/api/v1/workflows/" + workflowId + "/visibility",
                ownerToken, Map.of("visibility", "unlisted"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (String) response.getBody().get("shareKey");
    }

    private UserView member(UserView admin, String prefix) {
        return userService.createUser(admin.id(), uniqueEmail(prefix), "password123", prefix, "MEMBER");
    }

    private ResponseEntity<Map> call(HttpMethod method, String path, String token, Object body) {
        return call(method, path, token, body, Map.class);
    }

    private <T> ResponseEntity<T> call(HttpMethod method, String path, String token, Object body, Class<T> type) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return restTemplate.exchange(path, method, new HttpEntity<>(body, headers), type);
    }
}
