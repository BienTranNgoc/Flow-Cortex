package com.flowcortex.app.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.shared.security.authz.Permission;
import com.flowcortex.user.api.AdminAssignee;
import com.flowcortex.user.api.AdminPermissions;
import com.flowcortex.user.api.UserView;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/** {@code GET /api/v1/admin/assignees} and {@link AdminPermissions#holders} against the fully wired app. */
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class AdminAssigneesIT extends AbstractUserModuleIT {

    private static final String PASSWORD = "correct-password-1";
    private static final String SUPPORT_LIST = "/api/v1/admin/assignees?for=support.ticket.reply";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AdminPermissions permissions;

    private record Fixture(UserView an, UserView linh, UserView hoa, UserView binh, UserView ba, UserView tam,
            UserView minh) {
        Set<String> emails() {
            return Set.of(an.email(), linh.email(), hoa.email(), binh.email(), ba.email(), tam.email(),
                    minh.email());
        }
    }

    private Fixture seed() {
        String run = java.util.UUID.randomUUID().toString().substring(0, 8);
        UserView an = userService.createInitialAdmin("an-" + run + "@nodi.test", PASSWORD, "An");
        UserView linh = userService.createUser(an.id(), "linh-" + run + "@nodi.test", PASSWORD, "Linh",
                "CUSTOMER_SUPPORT");
        UserView hoa = userService.createUser(an.id(), "hoa-" + run + "@nodi.test", PASSWORD, "Hoa",
                "PLATFORM_OPERATOR");
        UserView binh = userService.createUser(an.id(), "binh-" + run + "@nodi.test", PASSWORD, "Binh",
                "CONTENT_EDITOR");
        UserView ba = userService.createUser(an.id(), "ba-" + run + "@nodi.test", PASSWORD, "Ba", "MEMBER");
        UserView tam = userService.createUser(an.id(), "tam-" + run + "@nodi.test", PASSWORD, "Tam",
                "CUSTOMER_SUPPORT");
        UserView minh = userService.createUser(an.id(), "minh-" + run + "@nodi.test", PASSWORD, "Minh",
                "OPERATION_MANAGER");
        jdbc.update("UPDATE app_user SET admin_disabled_at = now(), admin_disabled_by = ? WHERE id = ?", an.id(),
                tam.id());
        return new Fixture(an, linh, hoa, binh, ba, tam, minh);
    }

    private ResponseEntity<List<AdminAssignee>> list(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(headers),
                new ParameterizedTypeReference<>() {
                });
    }

    private static List<String> emailsIn(Fixture fixture, ResponseEntity<List<AdminAssignee>> response) {
        return response.getBody().stream().map(AdminAssignee::email).filter(fixture.emails()::contains).toList();
    }

    @Test
    void theSupportListHoldsTheActiveSuperAdminsAndReplyHolders() {
        Fixture f = seed();

        ResponseEntity<List<AdminAssignee>> response = list(SUPPORT_LIST, login(f.minh().email(), PASSWORD));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(emailsIn(f, response)).containsExactly(f.an().email(), f.hoa().email(), f.linh().email(),
                f.minh().email());
        assertThat(response.getBody()).extracting(AdminAssignee::name).contains("An", "Hoa", "Linh", "Minh");
    }

    @Test
    void aKeyRemovedFromARoleDropsItsHoldersFromTheList() {
        Fixture f = seed();
        jdbc.update("DELETE FROM role_permission WHERE role_code = 'PLATFORM_OPERATOR' "
                + "AND permission_key = 'support.ticket.reply'");
        try {
            ResponseEntity<List<AdminAssignee>> response = list(SUPPORT_LIST, login(f.minh().email(), PASSWORD));

            assertThat(emailsIn(f, response)).containsExactly(f.an().email(), f.linh().email(), f.minh().email());
        } finally {
            jdbc.update("INSERT INTO role_permission (role_code, permission_key) "
                    + "VALUES ('PLATFORM_OPERATOR', 'support.ticket.reply') ON CONFLICT DO NOTHING");
        }
    }

    @Test
    void holdsAgreesWithTheListAndRefusesDeactivatedSuspendedAndUnknownAccounts() {
        Fixture f = seed();
        UserView suspended = userService.createUser(f.an().id(), "suspended-" + f.an().email(), PASSWORD, "Sus",
                "CUSTOMER_SUPPORT");
        jdbc.update("UPDATE app_user SET status = 'SUSPENDED' WHERE id = ?", suspended.id());

        assertThat(permissions.holds(f.linh().id(), Permission.SUPPORT_TICKET_REPLY)).isTrue();
        assertThat(permissions.holds(f.an().id(), Permission.SUPPORT_TICKET_REPLY)).isTrue();
        assertThat(permissions.holds(f.binh().id(), Permission.SUPPORT_TICKET_REPLY)).isFalse();
        assertThat(permissions.holds(f.ba().id(), Permission.SUPPORT_TICKET_REPLY)).isFalse();
        assertThat(permissions.holds(f.tam().id(), Permission.SUPPORT_TICKET_REPLY)).isFalse();
        assertThat(permissions.holds(suspended.id(), Permission.SUPPORT_TICKET_REPLY)).isFalse();
        assertThat(permissions.holds(java.util.UUID.randomUUID(), Permission.SUPPORT_TICKET_REPLY)).isFalse();
        assertThat(permissions.holders(Permission.SUPPORT_TICKET_REPLY)).extracting(AdminAssignee::id)
                .doesNotContain(f.tam().id(), suspended.id());
    }

    @Test
    void aCallerWithoutTheAssignKeyIsForbidden() {
        Fixture f = seed();

        ResponseEntity<String> response = get(SUPPORT_LIST, login(f.hoa().email(), PASSWORD), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).doesNotContain(f.linh().email()).doesNotContain(f.an().email());
    }

    @Test
    void anUnofferedOrMissingListIsABadRequestNamingFor() {
        Fixture f = seed();
        String token = login(f.minh().email(), PASSWORD);

        for (String path : List.of("/api/v1/admin/assignees?for=payment.refund.process",
                "/api/v1/admin/assignees", "/api/v1/admin/assignees?for=")) {
            ResponseEntity<String> response = get(path, token, String.class);

            assertThat(response.getStatusCode()).as(path).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).as(path).contains("validation-failed").contains("\"for\"");
        }
    }
}
