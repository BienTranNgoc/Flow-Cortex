package com.flowcortex.app.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.user.api.UserView;
import java.util.concurrent.CompletableFuture;
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

/** {@code /api/v1/auth/profile/preferences} against the real, fully-wired app. */
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class AccountPreferencesIT extends AbstractUserModuleIT {

    private static final String PATH = "/api/v1/auth/profile/preferences";
    private static final String PASSWORD = "correct-password-1";

    private record Member(UserView view, String email, String token) {
    }

    private Member newMember() {
        UserView admin = userService.createInitialAdmin(uniqueEmail("prefs-admin"), PASSWORD, "Prefs Admin");
        String email = uniqueEmail("prefs-member");
        UserView member = userService.createUser(admin.id(), email, PASSWORD, "Prefs Member", "MEMBER");
        return new Member(member, email, login(email, PASSWORD));
    }

    private ResponseEntity<String> send(HttpMethod method, String path, String token, String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(path, method, new HttpEntity<>(json, headers), String.class);
    }

    private String read(String token) {
        ResponseEntity<String> response = get(PATH, token, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    @Test
    void unsupportedLocaleIs400AndNothingStored() {
        Member member = newMember();
        assertThat(send(HttpMethod.PUT, PATH + "/locale", member.token(), "{\"locale\":\"en\"}").getStatusCode())
                .isEqualTo(HttpStatus.OK);

        ResponseEntity<String> refused = send(HttpMethod.PUT, PATH + "/locale", member.token(), "{\"locale\":\"fr\"}");

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(refused.getBody()).contains("\"locale\"");
        assertThat(read(member.token())).contains("\"locale\":\"en\"");
    }

    @Test
    void unknownUiKeyIs400() {
        Member member = newMember();

        ResponseEntity<String> refused = send(HttpMethod.PATCH, PATH + "/ui", member.token(), "{\"fontSize\":\"large\"}");

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(read(member.token())).contains("\"ui\":{}");
    }

    @Test
    void nullUiValueIs400() {
        Member member = newMember();
        send(HttpMethod.PATCH, PATH + "/ui", member.token(), "{\"theme\":\"dark\"}");

        ResponseEntity<String> refused = send(HttpMethod.PATCH, PATH + "/ui", member.token(), "{\"theme\":null}");

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(read(member.token())).contains("\"theme\":\"dark\"");
    }

    @Test
    void badPrivacyValueIs400() {
        Member member = newMember();

        ResponseEntity<String> refused =
                send(HttpMethod.PATCH, PATH + "/privacy", member.token(), "{\"profile\":\"friends\"}");

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(read(member.token())).contains("\"privacy\":{\"profile\":\"everyone\",\"activity\":\"followers\","
                + "\"message\":\"everyone\",\"stats\":\"show\"}");
    }

    @Test
    void twoConcurrentPatchesOfDifferentKeysKeepBoth() {
        Member member = newMember();

        CompletableFuture<ResponseEntity<String>> theme =
                CompletableFuture.supplyAsync(() -> send(HttpMethod.PATCH, PATH + "/ui", member.token(),
                        "{\"theme\":\"dark\"}"));
        CompletableFuture<ResponseEntity<String>> sidebar =
                CompletableFuture.supplyAsync(() -> send(HttpMethod.PATCH, PATH + "/ui", member.token(),
                        "{\"sidebarMode\":\"rail\"}"));

        assertThat(theme.join().getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(sidebar.join().getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(read(member.token())).contains("\"theme\":\"dark\"").contains("\"sidebarMode\":\"rail\"");
    }

    @Test
    void suspendedAccountGets403() {
        UserView admin = userService.createInitialAdmin(uniqueEmail("prefs-admin"), PASSWORD, "Prefs Admin");
        String email = uniqueEmail("prefs-suspended");
        UserView member = userService.createUser(admin.id(), email, PASSWORD, "Soon Suspended", "MEMBER");
        String token = login(email, PASSWORD);
        userService.updateUser(admin.id(), member.id(), null, null, null, "SUSPENDED");

        assertThat(get(PATH, token, String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(send(HttpMethod.PATCH, PATH + "/ui", token, "{\"theme\":\"dark\"}").getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }
}
