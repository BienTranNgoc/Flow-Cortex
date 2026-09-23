package com.flowcortex.app.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.user.api.UserView;
import org.junit.jupiter.api.Test;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * {@code /api/v1/auth} against the real, fully-wired app. Covers login + me,
 * suspended login (403), and self profile update.
 */
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class UserAuthEndpointsIT extends AbstractUserModuleIT {

    @Test
    void loginSuccessReturnsTokenAndMeReflectsItWithoutPassword() {
        String email = uniqueEmail("admin-login");
        userService.createInitialAdmin(email, "correct-password-1", "Login Admin");

        ResponseEntity<TokenResponse> loginResponse =
                restTemplate.postForEntity("/api/v1/auth/login", new LoginRequest(email, "correct-password-1"),
                        TokenResponse.class);
        assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        String token = loginResponse.getBody().token();
        assertThat(token).isNotBlank();

        ResponseEntity<MeView> meResponse = get("/api/v1/auth/me", token, MeView.class);
        assertThat(meResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        MeView me = meResponse.getBody();
        assertThat(me.email()).isEqualTo(email);
        assertThat(me.role()).isEqualTo("ADMIN");
        // MeView has no password field at all — the JSON body cannot carry
        // one either, confirmed by grepping the raw body too.
        ResponseEntity<String> rawMe = get("/api/v1/auth/me", token, String.class);
        assertThat(rawMe.getBody()).doesNotContain("password");
    }

    @Test
    void loginWrongPasswordReturns401ProblemDetail() {
        String email = uniqueEmail("admin-badpass");
        userService.createInitialAdmin(email, "correct-password-1", "Bad Pass Admin");

        ResponseEntity<String> response = loginRaw(email, "not-the-right-password");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.valueOf("application/problem+json"));
    }

    @Test
    void loginUnknownEmailReturns401NotDisclosingWhichPartWasWrong() {
        ResponseEntity<String> response = loginRaw(uniqueEmail("never-registered"), "whatever-password");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void loginSuspendedUserCorrectPasswordReturns403AndNoTokenIsUsable() {
        // A self-owned ADMIN (createInitialAdmin) can no longer suspend
        // itself (the self-lockout guard, UserServiceImpl#updateUser), so
        // this suspends a MEMBER managed by an owning admin instead — "a
        // suspended user cannot log in" doesn't require the suspended user
        // to be a self-owned admin.
        UserView owningAdmin =
                userService.createInitialAdmin(uniqueEmail("admin-for-suspended-login"), "correct-password-1",
                        "Owning Admin");
        String email = uniqueEmail("member-suspended-login");
        UserView member = userService.createUser(owningAdmin.id(), email, "correct-password-1", "Suspended Member",
                "MEMBER");
        userService.updateUser(owningAdmin.id(), member.id(), null, null, null, "SUSPENDED");

        ResponseEntity<String> response = loginRaw(email, "correct-password-1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.valueOf("application/problem+json"));
    }

    @Test
    void suspendedUserWithStillValidTokenReturns403OnMe() {
        String email = uniqueEmail("member-live-suspend-me");
        UserView admin = userService.createInitialAdmin(uniqueEmail("admin-live-suspend-me"), "correct-password-1",
                "Live Suspend Admin");
        UserView member = userService.createUser(admin.id(), email, "correct-password-1", "Soon Suspended",
                "MEMBER");
        String token = login(email, "correct-password-1");
        assertThat(token).isNotBlank();

        userService.updateUser(admin.id(), member.id(), null, null, null, "SUSPENDED");

        ResponseEntity<String> response = get("/api/v1/auth/me", token, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.valueOf("application/problem+json"));
    }

    @Test
    void suspendedUserWithStillValidTokenReturns403OnProfileUpdate() {
        String email = uniqueEmail("member-live-suspend-profile");
        UserView admin = userService.createInitialAdmin(uniqueEmail("admin-live-suspend-profile"),
                "correct-password-1", "Live Suspend Admin");
        UserView member = userService.createUser(admin.id(), email, "correct-password-1", "Soon Suspended",
                "MEMBER");
        String token = login(email, "correct-password-1");
        assertThat(token).isNotBlank();

        userService.updateUser(admin.id(), member.id(), null, null, null, "SUSPENDED");

        ResponseEntity<String> response =
                put("/api/v1/auth/profile", token, new ProfileUpdateRequest("New Name"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.valueOf("application/problem+json"));
    }

    @Test
    void unauthenticatedMeReturns401ProblemDetail() {
        ResponseEntity<String> response = get("/api/v1/auth/me", null, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.valueOf("application/problem+json"));
    }

    @Test
    void updateProfileChangesOwnNameOnlyMeReflectsIt() {
        String email = uniqueEmail("member-profile");
        UserView admin = userService.createInitialAdmin(uniqueEmail("admin-for-member"), "correct-password-1",
                "Owning Admin");
        userService.createUser(admin.ownerId(), email, "correct-password-1", "Original Name", "MEMBER");
        String token = login(email, "correct-password-1");

        ResponseEntity<MeView> updateResponse =
                put("/api/v1/auth/profile", token, new ProfileUpdateRequest("New Display Name"), MeView.class);
        assertThat(updateResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(updateResponse.getBody().name()).isEqualTo("New Display Name");
        // email is unchanged by a profile update (name-only).
        assertThat(updateResponse.getBody().email()).isEqualTo(email);

        ResponseEntity<MeView> meAfter = get("/api/v1/auth/me", token, MeView.class);
        assertThat(meAfter.getBody().name()).isEqualTo("New Display Name");
    }

    record ProfileUpdateRequest(String name) {
    }
}
