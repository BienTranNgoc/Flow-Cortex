package com.flowcortex.app.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.user.api.UserView;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

/** {@code GET /api/v1/users/export} with a two-row limit, against the real, fully-wired app. */
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@TestPropertySource(properties = "flowcortex.users.export.max-rows=2")
class UserExportIT extends AbstractUserModuleIT {

    private static final String PASSWORD = "correct-password-1";
    private static final String BOM = "﻿";

    @Autowired
    private JdbcTemplate jdbc;

    private UserView seedAdmin(String label) {
        return userService.createInitialAdmin(uniqueEmail(label), PASSWORD, label);
    }

    private UserView seed(UserView admin, String name, String role) {
        return userService.createUser(admin.id(), uniqueEmail("exp"), PASSWORD, name, role);
    }

    private ResponseEntity<byte[]> export(UserView caller, String query) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(login(caller.email(), PASSWORD));
        headers.setAccept(List.of(MediaType.parseMediaType("text/csv"), MediaType.APPLICATION_PROBLEM_JSON));
        return restTemplate.exchange("/api/v1/users/export" + query, HttpMethod.GET, new HttpEntity<>(headers),
                byte[].class);
    }

    private static String text(ResponseEntity<byte[]> response) {
        return new String(response.getBody(), StandardCharsets.UTF_8);
    }

    @Test
    void overTheLimitIsUnprocessableWithTheCountAndNoFile() {
        UserView admin = seedAdmin("exp-over");
        seed(admin, "One", "MEMBER");
        seed(admin, "Two", "MEMBER");

        ResponseEntity<byte[]> response = export(admin, "");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(response.getHeaders().getContentType().toString()).startsWith("application/problem+json");
        assertThat(response.getHeaders().containsHeader(HttpHeaders.CONTENT_DISPOSITION)).isFalse();
        String body = text(response);
        assertThat(body).contains("\"code\":\"user.exportTooLarge\"").contains("3").contains("2");
    }

    @Test
    void atTheLimitTheFileHoldsEveryMatch() {
        UserView admin = seedAdmin("exp-at");
        UserView one = seed(admin, "Lê Thị \"Bé\"", "MEMBER");

        ResponseEntity<byte[]> response = export(admin, "");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = text(response);
        assertThat(body).startsWith(BOM + "ID,Name,Email,");
        String[] lines = body.split("\r\n");
        assertThat(lines).hasSize(3);
        assertThat(body).contains("\"" + one.id() + "\",\"Lê Thị \"\"Bé\"\"\"");
    }

    @Test
    void theFileHonoursTheListFilters() {
        UserView admin = seedAdmin("exp-filter");
        UserView suspended = seed(admin, "Suspended", "MEMBER");
        seed(admin, "Active", "MEMBER");
        jdbc.update("UPDATE app_user SET status = 'SUSPENDED' WHERE id = ?", suspended.id());

        ResponseEntity<byte[]> response = export(admin, "?state=suspended");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String[] lines = text(response).split("\r\n");
        assertThat(lines).hasSize(2);
        assertThat(lines[1]).contains(suspended.id().toString()).contains("\"suspended\"");
    }

    @Test
    void anUnknownRoleFilterIsABadRequest() {
        UserView admin = seedAdmin("exp-role");

        ResponseEntity<byte[]> response = export(admin, "?role=NO_SUCH_ROLE");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(text(response)).contains("\"code\":\"role.unknown\"");
    }

    @Test
    void customerSupportIsForbidden() {
        UserView admin = seedAdmin("exp-cs");
        UserView support = seed(admin, "Support", "CUSTOMER_SUPPORT");

        assertThat(export(support, "").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void operationManagerExportsTheirAdminsScope() {
        UserView admin = seedAdmin("exp-om");
        UserView manager = seed(admin, "Manager", "OPERATION_MANAGER");

        ResponseEntity<byte[]> response = export(manager, "");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = text(response);
        assertThat(body).contains(admin.id().toString()).contains(manager.id().toString());
    }

    @Test
    void contentDispositionNamesADatedCsv() {
        UserView admin = seedAdmin("exp-name");

        ResponseEntity<byte[]> response = export(admin, "");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType().toString()).startsWith("text/csv").containsIgnoringCase(
                "charset=utf-8");
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .matches("attachment; filename=\"users-\\d{8}-\\d{4}\\.csv\"");
    }
}
