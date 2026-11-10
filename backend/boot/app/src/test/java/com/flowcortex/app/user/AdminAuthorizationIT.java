package com.flowcortex.app.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.shared.security.authz.Permission;
import com.flowcortex.user.api.UserView;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Every governed request is decided by the caller's role keys and deactivation read on that
 * request, so a change made after a token was issued applies to the very next call.
 */
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class AdminAuthorizationIT extends AbstractUserModuleIT {

    private static final String PASSWORD = "correct-password-1";

    @Autowired
    private JdbcTemplate jdbc;

    private UserView seedAdmin(String label) {
        return userService.createInitialAdmin(uniqueEmail(label), PASSWORD, label);
    }

    private UserView seedUser(UserView admin, String label, String role) {
        return userService.createUser(admin.id(), uniqueEmail(label), PASSWORD, label, role);
    }

    @Test
    void aKeyRemovedFromARoleIsRefusedOnTheNextRequest() {
        UserView admin = seedAdmin("admin-key-removal");
        UserView support = seedUser(admin, "support-key-removal", "CUSTOMER_SUPPORT");
        String token = login(support.email(), PASSWORD);

        ResponseEntity<String> before = get("/api/v1/admin/runs", token, String.class);
        assertThat(before.getStatusCode()).isEqualTo(HttpStatus.OK);

        jdbc.update("DELETE FROM role_permission WHERE role_code = 'CUSTOMER_SUPPORT' AND permission_key = ?",
                Permission.WORKFLOW_RUN_VIEW.key());
        try {
            ResponseEntity<String> after = get("/api/v1/admin/runs", token, String.class);
            assertThat(after.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        } finally {
            jdbc.update("INSERT INTO role_permission (role_code, permission_key) VALUES ('CUSTOMER_SUPPORT', ?)"
                    + " ON CONFLICT DO NOTHING", Permission.WORKFLOW_RUN_VIEW.key());
        }
    }

    @Test
    void aDeactivatedAdminGetsAdminDisabledAndKeepsMemberRoutes() {
        UserView owner = seedAdmin("admin-deactivated-owner");
        UserView admin = seedUser(owner, "admin-deactivated", "ADMIN");
        String token = login(admin.email(), PASSWORD);
        jdbc.update("UPDATE app_user SET admin_disabled_at = now(), admin_disabled_by = ? WHERE id = ?",
                owner.id(), admin.id());

        ResponseEntity<String> adminRoute = get("/api/v1/admin/runs", token, String.class);
        ResponseEntity<String> memberRoute = get("/api/v1/workflows", token, String.class);

        assertThat(adminRoute.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(adminRoute.getBody()).contains("admin-disabled");
        assertThat(memberRoute.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void meListsTheEffectiveKeysOfAContentEditor() {
        UserView admin = seedAdmin("admin-me-keys");
        UserView editor = seedUser(admin, "editor-me-keys", "CONTENT_EDITOR");
        String token = login(editor.email(), PASSWORD);

        ResponseEntity<MeKeysView> response = get("/api/v1/auth/me", token, MeKeysView.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().adminDisabled()).isFalse();
        assertThat(response.getBody().permissions()).containsExactly(
                "content.help_article.manage", "content.help_article.view",
                "content.help_topic.manage", "content.help_topic.view");
    }

    @Test
    void anEndUserIsRefusedOnAGovernedRouteAndAStaffMemberOutsideItsKeys() {
        UserView admin = seedAdmin("admin-refusals");
        UserView member = seedUser(admin, "member-refusals", "MEMBER");
        UserView editor = seedUser(admin, "editor-refusals", "CONTENT_EDITOR");

        ResponseEntity<String> memberOnRuns = get("/api/v1/admin/runs", login(member.email(), PASSWORD), String.class);
        ResponseEntity<String> editorOnRuns = get("/api/v1/admin/runs", login(editor.email(), PASSWORD), String.class);
        ResponseEntity<String> editorOnUsers = get("/api/v1/users", login(editor.email(), PASSWORD), String.class);

        assertThat(memberOnRuns.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(editorOnRuns.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(editorOnUsers.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void anAnonymousCallerOnAnAdminRouteIsUnauthorized() {
        ResponseEntity<String> response = get("/api/v1/admin/runs", null, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    record MeKeysView(List<String> permissions, boolean adminDisabled) {
    }
}
