package com.flowcortex.app.user;

import com.flowcortex.AbstractPostgresBootTest;
import com.flowcortex.user.api.UserService;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

/**
 * Shared HTTP-level test support for the {@code /api/v1/auth} and {@code
 * /api/v1/users} endpoint ITs. Not itself annotated with {@code
 * @SpringBootTest}/{@code @Testcontainers} — each concrete subclass carries
 * those, matching this codebase's existing IT pattern ({@code
 * SecurityFilterChainIT}, {@code OpenApiSnapshotTest}).
 *
 * <p>Preconditions ("Given a seeded ADMIN...") are set up directly through
 * the {@link UserService} {@code api} port — the only legitimate way to
 * bootstrap the FIRST user of an owner scope, since {@code POST /users}
 * itself requires an already-authenticated ADMIN (the self-owned-ADMIN
 * decision; see {@code UserService#createInitialAdmin}'s javadoc). The
 * WHEN/THEN of each scenario goes through the real HTTP surface.
 */
abstract class AbstractUserModuleIT extends AbstractPostgresBootTest {

    @Autowired
    protected UserService userService;

    @Autowired
    protected TestRestTemplate restTemplate;

    protected static String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@example.com";
    }

    /** Logs in over real HTTP and returns the minted token, or {@code null} on a non-200 response. */
    protected String login(String email, String password) {
        ResponseEntity<TokenResponse> response =
                restTemplate.postForEntity("/api/v1/auth/login", new LoginRequest(email, password),
                        TokenResponse.class);
        TokenResponse body = response.getBody();
        return body != null ? body.token() : null;
    }

    protected ResponseEntity<String> loginRaw(String email, String password) {
        return restTemplate.postForEntity("/api/v1/auth/login", new LoginRequest(email, password), String.class);
    }

    protected <T> ResponseEntity<T> get(String path, String token, Class<T> responseType) {
        return restTemplate.exchange(path, HttpMethod.GET, bearerEntity(token, null), responseType);
    }

    protected <T> ResponseEntity<T> post(String path, String token, Object body, Class<T> responseType) {
        return restTemplate.exchange(path, HttpMethod.POST, bearerEntity(token, body), responseType);
    }

    protected <T> ResponseEntity<T> put(String path, String token, Object body, Class<T> responseType) {
        return restTemplate.exchange(path, HttpMethod.PUT, bearerEntity(token, body), responseType);
    }

    protected <T> ResponseEntity<T> delete(String path, String token, Class<T> responseType) {
        return restTemplate.exchange(path, HttpMethod.DELETE, bearerEntity(token, null), responseType);
    }

    private static HttpEntity<Object> bearerEntity(String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return new HttpEntity<>(body, headers);
    }

    record LoginRequest(String email, String password) {
    }

    record TokenResponse(String token) {
    }

    record MeView(UUID id, String email, String name, String role, String status) {
    }

    record CreateUserRequestView(String email, String password, String name, String role) {
    }

    record UpdateUserRequestView(String email, String name, String role, String status) {
    }

    record UserResponseView(
            UUID id, String email, String name, String role, String status, Instant createdAt, Instant updatedAt) {
    }
}
