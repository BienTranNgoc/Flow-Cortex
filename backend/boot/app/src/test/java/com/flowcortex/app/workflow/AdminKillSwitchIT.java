package com.flowcortex.app.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.user.api.UserView;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * {@code flowcortex.admin.enabled=false}: every admin endpoint answers 503 Problem Detail, while
 * the role checks still come first and {@code /api/v1/users} keeps working. A dedicated context,
 * since the property binds once at startup.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "flowcortex.admin.enabled=false")
@AutoConfigureTestRestTemplate
class AdminKillSwitchIT extends AbstractWorkflowHttpIT {

    record ProblemView(int status, String detail) {
    }

    @Test
    void anAdminGets503ProblemDetailOnEveryAdminEndpoint() {
        String token = loginAs(newUser("admin-disabled"));

        for (String path : List.of("/api/v1/admin/runs", "/api/v1/admin/media/jobs")) {
            ResponseEntity<ProblemView> response = get(path, token, ProblemView.class);

            assertThat(response.getStatusCode()).as(path).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(response.getHeaders().getContentType()).as(path).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
            assertThat(response.getBody().detail()).as(path).isEqualTo("Admin console is currently disabled");
        }
    }

    @Test
    void theRoleChecksStillComeFirstAndTheUsersEndpointsKeepTheirBehaviour() {
        UserView admin = newUser("admin-disabled-users");
        UserView member = userService.createUser(admin.id(), uniqueEmail("member-disabled"), "password123",
                "Member", "MEMBER");

        assertThat(get("/api/v1/admin/runs", null, String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(get("/api/v1/admin/runs", loginAs(member), String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("/api/v1/users", loginAs(admin), String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void theMemberApiKeepsWorking() {
        UserView admin = newUser("admin-disabled-member-api");
        UserView member = userService.createUser(admin.id(), uniqueEmail("member-api"), "password123", "Member",
                "MEMBER");

        assertThat(get("/api/v1/workflows", loginAs(member), String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
