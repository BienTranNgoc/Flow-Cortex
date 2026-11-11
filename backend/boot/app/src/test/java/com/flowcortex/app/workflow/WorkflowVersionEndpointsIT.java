package com.flowcortex.app.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.user.api.UserView;
import com.flowcortex.workflow.api.NewWorkflowStep;
import com.flowcortex.workflow.api.WorkflowView;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * {@code /api/v1/workflows/{id}/versions}, {@code /hotfix}, {@code /diff}, {@code /published-version} and
 * {@code /pin} against the real, fully-wired app: status codes, the owner/non-owner split and the refusal shapes.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class WorkflowVersionEndpointsIT extends AbstractWorkflowHttpIT {

    @Test
    void aSecondUnchangedSaveIsOkAndDeduplicated() {
        UserView owner = newUser("http-version-save");
        WorkflowView workflow = newWorkflow(owner.id(), false, new NewWorkflowStep("only", "echo", null));
        String token = loginAs(owner);
        String path = "/api/v1/workflows/" + workflow.id() + "/versions";

        ResponseEntity<Map> first = call(HttpMethod.POST, path, token, Map.of("changelog", "first cut"), null);
        ResponseEntity<Map> second = call(HttpMethod.POST, path, token, null, null);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(first.getBody()).containsEntry("deduplicated", false);
        assertThat((Map<String, Object>) first.getBody().get("version"))
                .containsEntry("version", 1).containsEntry("source", "manual").containsEntry("changelog", "first cut");
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody()).containsEntry("deduplicated", true);
    }

    @Test
    void aLimitOverTheCapIs400NamingTheParameter() {
        UserView owner = newUser("http-version-limit");
        WorkflowView workflow = newWorkflow(owner.id(), false, new NewWorkflowStep("only", "echo", null));

        ResponseEntity<Map> response = call(HttpMethod.GET,
                "/api/v1/workflows/" + workflow.id() + "/versions?limit=101", loginAs(owner), null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat((Map<String, Object>) response.getBody().get("errors")).containsKey("limit");
    }

    @Test
    void aNonOwnerReadsAReleasedVersionAsASkeletonAndPinsIt() {
        UserView owner = newUser("http-version-owner");
        UserView member = newUser("http-version-member");
        WorkflowView workflow = newWorkflow(owner.id(), false, new NewWorkflowStep("only", "echo", null));
        String ownerToken = loginAs(owner);
        String memberToken = loginAs(member);
        String base = "/api/v1/workflows/" + workflow.id();
        call(HttpMethod.PATCH, base, ownerToken, Map.of("shared", true, "status", "published"), null);

        ResponseEntity<Map> detail = call(HttpMethod.GET, base + "/versions/1", memberToken, null, null);
        ResponseEntity<Map> saveByMember = call(HttpMethod.POST, base + "/versions", memberToken, null, null);
        ResponseEntity<Map> pinned = call(HttpMethod.POST, base + "/pin", memberToken, Map.of("version", 1), null);
        ResponseEntity<Map> pin = call(HttpMethod.GET, base + "/pin", memberToken, null, null);
        ResponseEntity<Map> unpinned = call(HttpMethod.DELETE, base + "/pin", memberToken, null, null);
        ResponseEntity<Map> unpinnedAgain = call(HttpMethod.DELETE, base + "/pin", memberToken, null, null);

        assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> snapshot = (Map<String, Object>) detail.getBody().get("snapshot");
        assertThat(snapshot).containsEntry("skeleton", true);
        assertThat((List<?>) snapshot.get("steps")).singleElement().satisfies(step ->
                assertThat((Map<String, Object>) step).containsEntry("config", null).containsEntry("inputRef", null));
        assertThat(saveByMember.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(pinned.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(pin.getBody()).containsEntry("pinnedVersion", 1);
        assertThat(unpinned.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(unpinnedAgain.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void aWorkflowTheCallerCannotSeeIsNotFound() {
        UserView owner = newUser("http-version-hidden-owner");
        UserView stranger = newUser("http-version-hidden-stranger");
        WorkflowView workflow = newWorkflow(owner.id(), false, new NewWorkflowStep("only", "echo", null));

        ResponseEntity<Map> response = call(HttpMethod.GET, "/api/v1/workflows/" + workflow.id() + "/versions",
                loginAs(stranger), null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void anInvalidDiffSideIs400NamingTheParameter() {
        UserView owner = newUser("http-version-diff");
        WorkflowView workflow = newWorkflow(owner.id(), false, new NewWorkflowStep("only", "echo", null));

        ResponseEntity<Map> response = call(HttpMethod.GET,
                "/api/v1/workflows/" + workflow.id() + "/diff?source=nonsense", loginAs(owner), null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat((Map<String, Object>) response.getBody().get("errors")).containsKey("source");
    }

    @Test
    void renamingAPublishVersionIsRefusedInVietnameseForAVietnameseCaller() {
        UserView owner = newUser("http-version-vi");
        WorkflowView workflow = newWorkflow(owner.id(), false, new NewWorkflowStep("only", "echo", null));
        String token = loginAs(owner);
        String base = "/api/v1/workflows/" + workflow.id();
        call(HttpMethod.PATCH, base, token, Map.of("shared", true, "status", "published"), null);

        ResponseEntity<Map> english = call(HttpMethod.PATCH, base + "/versions/1", token,
                Map.of("changelog", "renamed"), null);
        ResponseEntity<Map> vietnamese = call(HttpMethod.PATCH, base + "/versions/1", token,
                Map.of("changelog", "renamed"), "vi");

        assertThat(english.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat((Map<String, Object>) english.getBody().get("errors"))
                .containsEntry("source", "Only saved and hotfix versions can be renamed");
        assertThat(vietnamese.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat((Map<String, Object>) vietnamese.getBody().get("errors"))
                .containsEntry("source", "Chỉ phiên bản lưu thủ công và bản sửa nhanh mới được đổi tên");
    }

    private ResponseEntity<Map> call(HttpMethod method, String path, String token, Object body, String language) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        if (body != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        if (language != null) {
            headers.set(HttpHeaders.ACCEPT_LANGUAGE, language);
        }
        return restTemplate.exchange(path, method, new HttpEntity<>(body, headers), Map.class);
    }
}
