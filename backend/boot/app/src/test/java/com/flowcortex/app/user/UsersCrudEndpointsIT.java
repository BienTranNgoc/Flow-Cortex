package com.flowcortex.app.user;

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
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * {@code /api/v1/users} against the real, fully-wired app. Covers create +
 * validation + conflict, admin suspending a user, cross-scope 404,
 * MEMBER/unauthenticated locked out, and admin-mediated email correction.
 * This also test-locks the AccessDeniedException→403 plumbing via the
 * MEMBER→403 case.
 */
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class UsersCrudEndpointsIT extends AbstractUserModuleIT {

    private String adminToken(UserView admin) {
        return login(admin.email(), "correct-password-1");
    }

    private UserView seedAdmin(String label) {
        return userService.createInitialAdmin(uniqueEmail(label), "correct-password-1", label);
    }

    @Test
    void createUserValidReturns201ActiveNoPassword() {
        UserView admin = seedAdmin("admin-create");
        String token = adminToken(admin);
        String newEmail = uniqueEmail("created-member");

        ResponseEntity<UserResponseView> response = post("/api/v1/users", token,
                new CreateUserRequestView(newEmail, "member-password-1", "New Member", "MEMBER"),
                UserResponseView.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UserResponseView created = response.getBody();
        assertThat(created.email()).isEqualTo(newEmail);
        assertThat(created.status()).isEqualTo("ACTIVE");
        assertThat(created.role()).isEqualTo("MEMBER");

        List<UserView> list = userService.listUsers(admin.id());
        assertThat(list).extracting(UserView::email).contains(newEmail);
    }

    @Test
    void createUserInvalidEmailReturns400NamingTheField() {
        UserView admin = seedAdmin("admin-invalid-email");
        String token = adminToken(admin);

        ResponseEntity<String> response = post("/api/v1/users", token,
                new CreateUserRequestView("not-an-email", "member-password-1", "New Member", "MEMBER"),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.valueOf("application/problem+json"));
        assertThat(response.getBody()).contains("\"email\"");
    }

    @Test
    void createUserDuplicateEmailReturns409AndOnlyOneRowRemains() {
        UserView admin = seedAdmin("admin-duplicate");
        String token = adminToken(admin);
        String duplicateEmail = uniqueEmail("duplicate-target");

        ResponseEntity<UserResponseView> first = post("/api/v1/users", token,
                new CreateUserRequestView(duplicateEmail, "member-password-1", "First", "MEMBER"),
                UserResponseView.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> second = post("/api/v1/users", token,
                new CreateUserRequestView(duplicateEmail, "member-password-1", "Second", "MEMBER"), String.class);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getHeaders().getContentType()).isEqualTo(MediaType.valueOf("application/problem+json"));
        long matching =
                userService.listUsers(admin.id()).stream().filter(u -> u.email().equals(duplicateEmail)).count();
        assertThat(matching).isEqualTo(1);
    }

    @Test
    void suspendUserSetsStatusSuspended() {
        UserView admin = seedAdmin("admin-suspend");
        String token = adminToken(admin);
        UserView target =
                userService.createUser(admin.id(), uniqueEmail("to-suspend"), "member-password-1", "Target",
                        "MEMBER");

        ResponseEntity<UserResponseView> response = put("/api/v1/users/" + target.id(), token,
                new UpdateUserRequestView(null, null, null, "SUSPENDED"), UserResponseView.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().status()).isEqualTo("SUSPENDED");
    }

    @Test
    void getUserOutsideCallersScopeReturns404NotDisclosingTheOtherScopesData() {
        UserView adminA = seedAdmin("admin-scope-a");
        UserView adminB = seedAdmin("admin-scope-b");
        UserView userInScopeB =
                userService.createUser(adminB.id(), uniqueEmail("scope-b-secret"), "member-password-1",
                        "Scope B Secret Person", "MEMBER");
        String tokenA = adminToken(adminA);

        ResponseEntity<String> response = get("/api/v1/users/" + userInScopeB.id(), tokenA, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.valueOf("application/problem+json"));
        assertThat(response.getBody()).doesNotContain("Scope B Secret Person");
        assertThat(response.getBody()).doesNotContain(userInScopeB.email());
    }

    @Test
    void memberCannotListUsersReturns403NoDataLeakedNoMutation() {
        UserView admin = seedAdmin("admin-for-member-403");
        String memberEmail = uniqueEmail("blocked-member");
        userService.createUser(admin.id(), memberEmail, "member-password-1", "Blocked Member", "MEMBER");
        String memberToken = login(memberEmail, "member-password-1");

        ResponseEntity<String> listResponse = get("/api/v1/users", memberToken, String.class);
        assertThat(listResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(listResponse.getHeaders().getContentType())
                .isEqualTo(MediaType.valueOf("application/problem+json"));

        ResponseEntity<String> createResponse = post("/api/v1/users", memberToken,
                new CreateUserRequestView(uniqueEmail("should-not-be-created"), "password123", "Nope", "MEMBER"),
                String.class);
        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(userService.listUsers(admin.id()).stream().map(UserView::email))
                .noneMatch(email -> email.contains("should-not-be-created"));
    }

    @Test
    void unauthenticatedListUsersReturns401() {
        ResponseEntity<String> response = get("/api/v1/users", null, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void adminCorrectsEmailMemberCanThenLoginWithTheNewEmail() {
        UserView admin = seedAdmin("admin-email-fix");
        String token = adminToken(admin);
        UserView member = userService.createUser(admin.id(), uniqueEmail("typo-email"), "member-password-1",
                "Typo Member", "MEMBER");
        String correctedEmail = uniqueEmail("corrected-email");

        ResponseEntity<UserResponseView> updateResponse = put("/api/v1/users/" + member.id(), token,
                new UpdateUserRequestView(correctedEmail, null, null, null), UserResponseView.class);
        assertThat(updateResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(updateResponse.getBody().email()).isEqualTo(correctedEmail);

        ResponseEntity<TokenResponse> loginWithNew =
                restTemplate.postForEntity("/api/v1/auth/login", new LoginRequest(correctedEmail, "member-password-1"),
                        TokenResponse.class);
        assertThat(loginWithNew.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void updateUserEmailCollidingWithAnExistingUserReturns409() {
        UserView admin = seedAdmin("admin-email-collision");
        String token = adminToken(admin);
        UserView existing = userService.createUser(admin.id(), uniqueEmail("already-taken"), "member-password-1",
                "Existing", "MEMBER");
        UserView toRename = userService.createUser(admin.id(), uniqueEmail("to-rename"), "member-password-1",
                "To Rename", "MEMBER");

        ResponseEntity<String> response = put("/api/v1/users/" + toRename.id(), token,
                new UpdateUserRequestView(existing.email(), null, null, null), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void adminDeletingOwnAccountReturns409AndRowStillPresent() {
        UserView admin = seedAdmin("admin-self-delete");
        String token = adminToken(admin);

        ResponseEntity<String> response = delete("/api/v1/users/" + admin.id(), token, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.valueOf("application/problem+json"));
        assertThat(userService.getUser(admin.id(), admin.id())).isNotNull();
    }

    @Test
    void suspendedAdminWithStillValidTokenReturns403OnListUsers() {
        // G1: only an ADMIN-role token ever reaches this controller
        // (@PreAuthorize checks the JWT role claim), so the reachable
        // "suspended principal" here is a delegated ADMIN — role=ADMIN but
        // owned by another admin, exactly as CreateUserRequest already
        // allows — suspended by their owner while their own token is still
        // valid.
        UserView owner = seedAdmin("admin-owner-for-suspend");
        String ownerToken = adminToken(owner);
        UserView delegatedAdmin = userService.createUser(owner.id(), uniqueEmail("delegated-admin"),
                "member-password-1", "Delegated Admin", "ADMIN");
        String delegatedToken = login(delegatedAdmin.email(), "member-password-1");

        ResponseEntity<String> beforeSuspend = get("/api/v1/users", delegatedToken, String.class);
        assertThat(beforeSuspend.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<UserResponseView> suspendResponse = put("/api/v1/users/" + delegatedAdmin.id(), ownerToken,
                new UpdateUserRequestView(null, null, null, "SUSPENDED"), UserResponseView.class);
        assertThat(suspendResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> afterSuspend = get("/api/v1/users", delegatedToken, String.class);

        assertThat(afterSuspend.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(afterSuspend.getHeaders().getContentType()).isEqualTo(MediaType.valueOf("application/problem+json"));
    }

    @Test
    void adminSelfSuspendReturns409AndRowUnchanged() {
        UserView admin = seedAdmin("admin-self-suspend");
        String token = adminToken(admin);

        ResponseEntity<String> response = put("/api/v1/users/" + admin.id(), token,
                new UpdateUserRequestView(null, null, null, "SUSPENDED"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.valueOf("application/problem+json"));
        assertThat(userService.getUser(admin.id(), admin.id()).status()).isEqualTo("ACTIVE");
    }

    @Test
    void adminSelfDowngradeRoleReturns409AndRowUnchanged() {
        UserView admin = seedAdmin("admin-self-downgrade");
        String token = adminToken(admin);

        ResponseEntity<String> response = put("/api/v1/users/" + admin.id(), token,
                new UpdateUserRequestView(null, null, "MEMBER", null), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.valueOf("application/problem+json"));
        assertThat(userService.getUser(admin.id(), admin.id()).role()).isEqualTo("ADMIN");
    }

    @Test
    void adminSelfUpdateNoOpStatusAndRoleStillSucceeds() {
        UserView admin = seedAdmin("admin-self-noop");
        String token = adminToken(admin);

        ResponseEntity<UserResponseView> response = put("/api/v1/users/" + admin.id(), token,
                new UpdateUserRequestView(null, "Renamed Self", "ADMIN", "ACTIVE"), UserResponseView.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().name()).isEqualTo("Renamed Self");
        assertThat(response.getBody().role()).isEqualTo("ADMIN");
        assertThat(response.getBody().status()).isEqualTo("ACTIVE");
    }

    @Test
    void deleteUserRemovesTheRow() {
        UserView admin = seedAdmin("admin-delete");
        String token = adminToken(admin);
        UserView target = userService.createUser(admin.id(), uniqueEmail("to-delete"), "member-password-1",
                "To Delete", "MEMBER");

        ResponseEntity<Void> deleteResponse = delete("/api/v1/users/" + target.id(), token, Void.class);
        assertThat(deleteResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<String> getAfterDelete = get("/api/v1/users/" + target.id(), token, String.class);
        assertThat(getAfterDelete.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
