package com.flowcortex.app.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.user.application.OpaqueTokens;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.boot.resttestclient.TestRestTemplate;
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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

/**
 * The MCP authorization server over real HTTP and the real database, switch on: registration, the
 * consent check and grant with a real step-up proof, the code exchange and its one-time, PKCE and
 * client bindings, revocation scope, the per-address limits and the metadata document.
 */
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "flowcortex.auth.personal-access-tokens.enabled=true",
        "flowcortex.auth.mcp-oauth.enabled=true",
        "flowcortex.app.public-url=https://app.nodi.vn/"})
@AutoConfigureTestRestTemplate
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class McpOAuthIT extends AbstractUserModuleIT {

    private static final String PASSWORD = "mcp-oauth-password-123";
    private static final String CALLBACK = "https://claude.ai/api/mcp/auth_callback";
    private static final String VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
    private static final String CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Carries the registration and code of the first half of the restart test into the second context. */
    private static Registered beforeRestart;
    private static String codeBeforeRestart;

    @Autowired
    private JdbcTemplate jdbc;

    record Account(UUID id, String email, String session) {
    }

    record Registered(String clientId, String clientSecret) {
    }

    record ProofView(String stepUpProof) {
    }

    record ProblemView(int status, String type, String code, String detail, String error) {
    }

    record MeView(UUID id, String email) {
    }

    private TestRestTemplate client() {
        return new TestRestTemplate(new RestTemplateBuilder()
                .requestFactoryBuilder(ClientHttpRequestFactoryBuilder.jdk()).rootUri(restTemplate.getRootUri()));
    }

    private static String freshAddress() {
        return "198.51.100." + (1 + (int) (Math.random() * 253));
    }

    private Account admin(String prefix) {
        String email = uniqueEmail(prefix);
        UUID id = userService.createInitialAdmin(email, PASSWORD, "Mcp Admin").id();
        return new Account(id, email, login(email, PASSWORD));
    }

    private String proof(Account account) {
        return json("/api/v1/me/step-up", HttpMethod.POST, account.session(),
                Map.of("password", PASSWORD, "purpose", "PAT_CREATE"), ProofView.class).getBody().stepUpProof();
    }

    private <T> ResponseEntity<T> json(String path, HttpMethod method, String bearer, Object body, Class<T> type) {
        HttpHeaders headers = new HttpHeaders();
        if (bearer != null) {
            headers.setBearerAuth(bearer);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.ALL));
        return client().exchange(path, method, new HttpEntity<>(body, headers), type);
    }

    private ResponseEntity<String> register(Map<String, Object> body, String address) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Forwarded-For", address);
        return client().exchange("/api/v1/oauth/register", HttpMethod.POST, new HttpEntity<>(body, headers),
                String.class);
    }

    private Registered registerClient(String method) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("client_name", "Claude");
        body.put("redirect_uris", List.of(CALLBACK));
        if (method != null) {
            body.put("token_endpoint_auth_method", method);
        }
        ResponseEntity<String> response = register(body, freshAddress());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<?, ?> parsed = JSON.readValue(response.getBody(), Map.class);
        return new Registered((String) parsed.get("client_id"), (String) parsed.get("client_secret"));
    }

    private Map<String, Object> authorizeBody(String clientId, String redirectUri, String proof) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("response_type", "code");
        body.put("client_id", clientId);
        body.put("redirect_uri", redirectUri);
        body.put("code_challenge", CHALLENGE);
        body.put("code_challenge_method", "S256");
        body.put("scope", "mcp");
        body.put("state", "xyz");
        if (proof != null) {
            body.put("stepUpProof", proof);
        }
        return body;
    }

    private ResponseEntity<String> grant(Account account, String clientId, String redirectUri, String proof) {
        return json("/api/v1/oauth/authorize", HttpMethod.POST, account.session(),
                authorizeBody(clientId, redirectUri, proof), String.class);
    }

    private String consentCode(Account account, String clientId) {
        ResponseEntity<String> response = grant(account, clientId, CALLBACK, proof(account));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String redirectTo = (String) JSON.readValue(response.getBody(), Map.class).get("redirectTo");
        assertThat(redirectTo).startsWith(CALLBACK + "?code=").endsWith("&state=xyz");
        return UriComponentsBuilder.fromUriString(redirectTo).build().getQueryParams().getFirst("code");
    }

    private ResponseEntity<String> postForm(String path, Map<String, String> form, String basicId,
            String basicSecret, String address) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.set("X-Forwarded-For", address);
        if (basicId != null) {
            headers.setBasicAuth(basicId, basicSecret);
        }
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        form.forEach(body::add);
        return client().exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> exchange(Registered client, String code, String verifier, String redirectUri) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", code);
        form.put("code_verifier", verifier);
        form.put("redirect_uri", redirectUri);
        form.put("client_id", client.clientId());
        if (client.clientSecret() != null) {
            form.put("client_secret", client.clientSecret());
        }
        return postForm("/api/v1/oauth/token", form, null, null, freshAddress());
    }

    private ResponseEntity<String> revoke(Registered client, String token) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("token", token);
        form.put("client_id", client.clientId());
        if (client.clientSecret() != null) {
            form.put("client_secret", client.clientSecret());
        }
        return postForm("/api/v1/oauth/revoke", form, null, null, freshAddress());
    }

    private static Map<?, ?> body(ResponseEntity<String> response) {
        return JSON.readValue(response.getBody(), Map.class);
    }

    private HttpStatus status(String path, HttpMethod method, String bearer) {
        return HttpStatus.valueOf(json(path, method, bearer, null, String.class).getStatusCode().value());
    }

    private long tokens(UUID userId) {
        return jdbc.queryForObject("SELECT count(*) FROM personal_access_token WHERE user_id = ?", Long.class,
                userId);
    }

    private long events(UUID userId, String type) {
        return jdbc.queryForObject("SELECT count(*) FROM auth_event WHERE user_id = ? AND type = ?", Long.class,
                userId, type);
    }

    @Test
    void theFullFlowRegistersConsentsExchangesUsesAndRevokes() {
        Account mai = admin("flow");
        Registered client = registerClient(null);
        String code = consentCode(mai, client.clientId());

        ResponseEntity<String> exchanged = exchange(client, code, VERIFIER, CALLBACK);

        assertThat(exchanged.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(exchanged.getHeaders().getCacheControl()).contains("no-store");
        assertThat(exchanged.getHeaders().getFirst("Pragma")).isEqualTo("no-cache");
        Map<?, ?> token = body(exchanged);
        String accessToken = (String) token.get("access_token");
        assertThat(accessToken).startsWith("fc_pat_");
        assertThat(token.get("token_type")).isEqualTo("Bearer");
        assertThat(((Number) token.get("expires_in")).longValue()).isEqualTo(7_776_000L);
        assertThat(token.get("scope")).isEqualTo("mcp");
        assertThat(jdbc.queryForList("SELECT name FROM personal_access_token WHERE user_id = ?", String.class,
                mai.id())).containsExactly("MCP: Claude");
        assertThat(json("/api/v1/auth/me", HttpMethod.GET, accessToken, null, MeView.class).getBody().id())
                .isEqualTo(mai.id());
        ResponseEntity<ProblemView> admin = json("/api/v1/admin/runs", HttpMethod.GET, accessToken, null,
                ProblemView.class);
        assertThat(admin.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(admin.getBody().code()).isEqualTo("PAT_NOT_ALLOWED");

        ResponseEntity<String> revoked = revoke(client, accessToken);

        assertThat(revoked.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(revoked.getBody()).isEqualTo("{}");
        assertThat(status("/api/v1/auth/me", HttpMethod.GET, accessToken)).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(events(mai.id(), "OAUTH_CONSENT_GRANTED")).isEqualTo(1);
        assertThat(events(mai.id(), "PAT_CREATED")).isEqualTo(1);
        assertThat(events(mai.id(), "PAT_REVOKED")).isEqualTo(1);
        assertThat(String.join(" ", jdbc.queryForList("SELECT metadata::text FROM auth_event WHERE user_id = ?",
                String.class, mai.id()))).contains(client.clientId()).doesNotContain(code).doesNotContain(VERIFIER)
                .doesNotContain(client.clientSecret()).doesNotContain(accessToken);
        assertThat(jdbc.queryForObject("SELECT code_hash FROM oauth_authorization_code WHERE client_id = ?",
                String.class, client.clientId())).isEqualTo(OpaqueTokens.hash(code));
    }

    @Test
    void theMetadataDocumentNamesTheIssuerAndEndpointsOfThePublicUrl() {
        ResponseEntity<String> response = client().getForEntity("/.well-known/oauth-authorization-server",
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> metadata = body(response);
        assertThat(metadata.get("issuer")).isEqualTo("https://app.nodi.vn");
        assertThat(metadata.get("authorization_endpoint")).isEqualTo("https://app.nodi.vn/oauth/authorize");
        assertThat(metadata.get("token_endpoint")).isEqualTo("https://app.nodi.vn/api/v1/oauth/token");
        assertThat(metadata.get("registration_endpoint")).isEqualTo("https://app.nodi.vn/api/v1/oauth/register");
        assertThat(metadata.get("revocation_endpoint")).isEqualTo("https://app.nodi.vn/api/v1/oauth/revoke");
        assertThat(metadata.get("response_types_supported")).isEqualTo(List.of("code"));
        assertThat(metadata.get("grant_types_supported")).isEqualTo(List.of("authorization_code"));
        assertThat(metadata.get("code_challenge_methods_supported")).isEqualTo(List.of("S256"));
        assertThat(metadata.get("scopes_supported")).isEqualTo(List.of("mcp"));
        List<String> methods = List.of("client_secret_post", "client_secret_basic", "none");
        assertThat(metadata.get("token_endpoint_auth_methods_supported")).isEqualTo(methods);
        assertThat(metadata.get("revocation_endpoint_auth_methods_supported")).isEqualTo(methods);
    }

    @Test
    void registrationStoresOnlyTheSecretHashAndRefusesBadMetadataWithoutStoringAnything() {
        long before = jdbc.queryForObject("SELECT count(*) FROM oauth_client", Long.class);
        Registered confidential = registerClient("client_secret_basic");

        assertThat(confidential.clientId()).matches("^mcp_[0-9a-f]{24}$");
        assertThat(confidential.clientSecret()).isNotBlank();
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM oauth_client WHERE client_id = ?",
                confidential.clientId());
        assertThat((String) row.get("client_secret_hash")).matches("^[0-9a-f]{64}$")
                .isEqualTo(OpaqueTokens.hash(confidential.clientSecret()));
        assertThat(row.values().stream().map(String::valueOf)).noneMatch(v -> v.contains(confidential.clientSecret()));

        for (String bad : List.of("http://evil.example/cb", "https://a.example/cb#frag", "https://u:pw@a.example/cb",
                "myapp://cb", "/relative")) {
            ResponseEntity<String> response = register(Map.of("redirect_uris", List.of(bad)), freshAddress());
            assertThat(response.getStatusCode()).as(bad).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(body(response).get("error")).as(bad).isEqualTo("invalid_redirect_uri");
        }
        ResponseEntity<String> grants = register(Map.of("redirect_uris", List.of(CALLBACK),
                "grant_types", List.of("client_credentials")), freshAddress());
        assertThat(grants.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(body(grants).get("error")).isEqualTo("invalid_client_metadata");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM oauth_client", Long.class)).isEqualTo(before + 1);

        ResponseEntity<String> loopback = register(Map.of("client_name", "Claude\u0000\nDesktop",
                "redirect_uris", List.of("http://127.0.0.1:33418/callback", "http://[::1]:9000/cb"),
                "token_endpoint_auth_method", "none", "grant_types", List.of("authorization_code", "refresh_token"),
                "unknown_extension", "ignored"), freshAddress());
        assertThat(loopback.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(body(loopback).get("client_secret")).isNull();
        assertThat(body(loopback).get("client_name")).isEqualTo("ClaudeDesktop");
        assertThat(body(loopback).get("grant_types")).isEqualTo(List.of("authorization_code"));
    }

    @Test
    void registrationIsLimitedPerAddressAndTheEleventhAnswers429() {
        String address = "203.0.113.7";
        for (int i = 0; i < 10; i++) {
            assertThat(register(Map.of("redirect_uris", List.of(CALLBACK)), address).getStatusCode())
                    .isEqualTo(HttpStatus.CREATED);
        }
        long before = jdbc.queryForObject("SELECT count(*) FROM oauth_client", Long.class);

        ResponseEntity<String> limited = register(Map.of("redirect_uris", List.of(CALLBACK)), address);

        assertThat(limited.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(Long.parseLong(limited.getHeaders().getFirst("Retry-After"))).isPositive();
        assertThat(body(limited).get("error")).isEqualTo("temporarily_unavailable");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM oauth_client", Long.class)).isEqualTo(before);
        assertThat(register(Map.of("redirect_uris", List.of(CALLBACK)), "203.0.113.8").getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void theThirtyFirstTokenRequestFromOneAddressAnswers429WithoutReadingTheCode() {
        String address = "198.51.100.4";
        Map<String, String> form = Map.of("grant_type", "authorization_code", "code", "x", "code_verifier", VERIFIER,
                "redirect_uri", CALLBACK, "client_id", "mcp_unknown");
        for (int i = 0; i < 30; i++) {
            assertThat(postForm("/api/v1/oauth/token", form, null, null, address).getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        ResponseEntity<String> limited = postForm("/api/v1/oauth/token", form, null, null, address);

        assertThat(limited.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(limited.getHeaders().getFirst("Retry-After")).isNotNull();
        assertThat(body(limited).get("error")).isEqualTo("temporarily_unavailable");
    }

    @Test
    void theCheckVerifiesTheRedirectUriAndReportsRedirectableFaultsOnlyForARegisteredClient() {
        Account mai = admin("check");
        Registered client = registerClient(null);
        String base = "/api/v1/oauth/authorize/request?response_type=code&client_id=" + client.clientId()
                + "&code_challenge=" + CHALLENGE + "&code_challenge_method=S256&state=xyz";

        ResponseEntity<String> valid = json(base + "&redirect_uri=" + CALLBACK, HttpMethod.GET, mai.session(), null,
                String.class);
        assertThat(valid.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> view = body(valid);
        assertThat(view.get("clientName")).isEqualTo("Claude");
        assertThat(view.get("eligible")).isEqualTo(true);
        assertThat(view.get("scope")).isEqualTo("mcp");
        assertThat(view.get("error")).isNull();

        ResponseEntity<ProblemView> evil = json(base + "&redirect_uri=https://evil.example/cb", HttpMethod.GET,
                mai.session(), null, ProblemView.class);
        assertThat(evil.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(evil.getBody().detail()).contains("did not register");
        ResponseEntity<ProblemView> unknown = json(base.replace(client.clientId(), "mcp_unknown")
                + "&redirect_uri=" + CALLBACK, HttpMethod.GET, mai.session(), null, ProblemView.class);
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(unknown.getBody().detail()).contains("not registered");

        assertThat(body(json(base.replace("response_type=code", "response_type=token") + "&redirect_uri=" + CALLBACK,
                HttpMethod.GET, mai.session(), null, String.class)).get("error")).isEqualTo("unsupported_response_type");
        assertThat(body(json(base.replace("S256", "plain") + "&redirect_uri=" + CALLBACK, HttpMethod.GET,
                mai.session(), null, String.class)).get("error")).isEqualTo("invalid_request");
        assertThat(body(json(base + "&scope=mcp%20admin&redirect_uri=" + CALLBACK, HttpMethod.GET, mai.session(),
                null, String.class)).get("error")).isEqualTo("invalid_scope");
        assertThat(status(base + "&redirect_uri=" + CALLBACK, HttpMethod.GET, null)).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void aGrantNeedsAFreshProofAndAnUnregisteredRedirectUriSpendsNothing() {
        Account mai = admin("grant");
        Registered client = registerClient(null);
        String proof = proof(mai);

        ResponseEntity<ProblemView> badRedirect = json("/api/v1/oauth/authorize", HttpMethod.POST, mai.session(),
                authorizeBody(client.clientId(), "https://evil.example/cb", proof), ProblemView.class);
        ResponseEntity<ProblemView> noProof = json("/api/v1/oauth/authorize", HttpMethod.POST, mai.session(),
                authorizeBody(client.clientId(), CALLBACK, null), ProblemView.class);

        assertThat(badRedirect.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(noProof.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(noProof.getBody().code()).isEqualTo("STEP_UP_REQUIRED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM oauth_authorization_code WHERE client_id = ?",
                Long.class, client.clientId())).isZero();
        ResponseEntity<String> granted = grant(mai, client.clientId(), CALLBACK, proof);
        assertThat(granted.getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<ProblemView> reused = json("/api/v1/oauth/authorize", HttpMethod.POST, mai.session(),
                authorizeBody(client.clientId(), CALLBACK, proof), ProblemView.class);
        assertThat(reused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(reused.getBody().code()).isEqualTo("STEP_UP_REQUIRED");
    }

    @Test
    void anAdminDeactivatedByRbacAndAMemberAreNotEligibleAndAtTheCapTheProofSurvives() {
        Account minh = admin("rbac");
        Registered client = registerClient(null);
        jdbc.update("UPDATE app_user SET admin_disabled_at = now() WHERE id = ?", minh.id());
        String link = "/api/v1/oauth/authorize/request?response_type=code&client_id=" + client.clientId()
                + "&code_challenge=" + CHALLENGE + "&code_challenge_method=S256&redirect_uri=" + CALLBACK;

        assertThat(body(json(link, HttpMethod.GET, minh.session(), null, String.class)).get("eligible"))
                .isEqualTo(false);
        ResponseEntity<ProblemView> refused = json("/api/v1/oauth/authorize", HttpMethod.POST, minh.session(),
                authorizeBody(client.clientId(), CALLBACK, "unused"), ProblemView.class);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(refused.getBody().code()).isEqualTo("PAT_NOT_ELIGIBLE");

        UUID ownerId = userService.createInitialAdmin(uniqueEmail("member-owner"), PASSWORD, "Owner").id();
        String memberEmail = uniqueEmail("member");
        userService.createUser(ownerId, memberEmail, PASSWORD, "Member", "MEMBER");
        String memberSession = login(memberEmail, PASSWORD);
        assertThat(body(json(link, HttpMethod.GET, memberSession, null, String.class)).get("eligible"))
                .isEqualTo(false);

        Account capped = admin("cap");
        for (int i = 0; i < 10; i++) {
            jdbc.update("INSERT INTO personal_access_token (id, user_id, name, token_hash, created_at, expires_at)"
                    + " VALUES (?, ?, ?, ?, now(), now() + interval '30 days')", UUID.randomUUID(), capped.id(),
                    "t" + i, OpaqueTokens.hash("fc_pat_cap-" + i + UUID.randomUUID()));
        }
        String proof = proof(capped);
        ResponseEntity<ProblemView> atCap = json("/api/v1/oauth/authorize", HttpMethod.POST, capped.session(),
                authorizeBody(client.clientId(), CALLBACK, proof), ProblemView.class);
        assertThat(atCap.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(atCap.getBody().code()).isEqualTo("PAT_LIMIT_REACHED");
        jdbc.update("DELETE FROM personal_access_token WHERE user_id = ?", capped.id());
        assertThat(grant(capped, client.clientId(), CALLBACK, proof).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void aTokenCannotCallTheConsentRoutes() {
        Account mai = admin("no-mint");
        Registered client = registerClient(null);
        String token = body(exchange(client, consentCode(mai, client.clientId()), VERIFIER, CALLBACK))
                .get("access_token").toString();
        long codesBefore = jdbc.queryForObject("SELECT count(*) FROM oauth_authorization_code", Long.class);

        ResponseEntity<ProblemView> grant = json("/api/v1/oauth/authorize", HttpMethod.POST, token,
                authorizeBody(client.clientId(), CALLBACK, "x"), ProblemView.class);
        ResponseEntity<ProblemView> check = json("/api/v1/oauth/authorize/request?client_id=" + client.clientId(),
                HttpMethod.GET, token, null, ProblemView.class);

        assertThat(grant.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(grant.getBody().code()).isEqualTo("PAT_NOT_ALLOWED");
        assertThat(check.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(check.getBody().code()).isEqualTo("PAT_NOT_ALLOWED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM oauth_authorization_code", Long.class))
                .isEqualTo(codesBefore);
    }

    @Test
    void aWrongVerifierBurnsTheCodeSoTheCorrectOneIsRefusedToo() {
        Account mai = admin("burn");
        Registered client = registerClient(null);
        String code = consentCode(mai, client.clientId());

        ResponseEntity<String> wrong = exchange(client, code, "wrong-verifier-wrong-verifier-wrong-verifier-1",
                CALLBACK);
        ResponseEntity<String> right = exchange(client, code, VERIFIER, CALLBACK);

        assertThat(wrong.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(body(wrong).get("error")).isEqualTo("invalid_grant");
        assertThat(right.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(body(right).get("error")).isEqualTo("invalid_grant");
        assertThat(tokens(mai.id())).isZero();
        assertThat(wrong.getBody() + right.getBody()).doesNotContain(code).doesNotContain(VERIFIER)
                .doesNotContain(client.clientSecret());
    }

    @Test
    void aCodeIsBoundToItsClientRedirectUriAndLifetime() {
        Account mai = admin("bound");
        Registered client = registerClient(null);
        Registered other = registerClient(null);
        String foreignCode = consentCode(mai, client.clientId());
        String slashCode = consentCode(mai, client.clientId());
        String expiredCode = consentCode(mai, client.clientId());
        jdbc.update("UPDATE oauth_authorization_code SET expires_at = now() - interval '1 second' WHERE code_hash = ?",
                OpaqueTokens.hash(expiredCode));

        assertThat(body(exchange(other, foreignCode, VERIFIER, CALLBACK)).get("error")).isEqualTo("invalid_grant");
        assertThat(body(exchange(client, slashCode, VERIFIER, CALLBACK + "/")).get("error"))
                .isEqualTo("invalid_grant");
        assertThat(body(exchange(client, expiredCode, VERIFIER, CALLBACK)).get("error")).isEqualTo("invalid_grant");
        assertThat(tokens(mai.id())).isZero();
    }

    @Test
    void aReplayedCodeRevokesTheTokenItProducedAndIsAudited() {
        Account mai = admin("replay");
        Registered client = registerClient(null);
        String code = consentCode(mai, client.clientId());
        String token = body(exchange(client, code, VERIFIER, CALLBACK)).get("access_token").toString();
        assertThat(status("/api/v1/auth/me", HttpMethod.GET, token)).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> replay = exchange(client, code, VERIFIER, CALLBACK);

        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(body(replay).get("error")).isEqualTo("invalid_grant");
        assertThat(status("/api/v1/auth/me", HttpMethod.GET, token)).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(events(mai.id(), "OAUTH_CODE_REPLAYED")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT metadata::text FROM auth_event WHERE user_id = ? AND"
                + " type = 'OAUTH_CODE_REPLAYED'", String.class, mai.id())).contains(client.clientId())
                .doesNotContain(code);
    }

    @Test
    void twoConcurrentExchangesOfOneCodeYieldExactlyOneToken() throws Exception {
        Account mai = admin("race");
        Registered client = registerClient(null);
        String code = consentCode(mai, client.clientId());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<ResponseEntity<String>>> calls = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                calls.add(pool.submit(() -> {
                    start.await();
                    return exchange(client, code, VERIFIER, CALLBACK);
                }));
            }
            start.countDown();
            List<HttpStatus> statuses = new ArrayList<>();
            for (Future<ResponseEntity<String>> call : calls) {
                statuses.add(HttpStatus.valueOf(call.get(30, TimeUnit.SECONDS).getStatusCode().value()));
            }

            assertThat(statuses).containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.BAD_REQUEST);
            assertThat(tokens(mai.id())).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void anAccountDemotedBetweenConsentAndExchangeGetsInvalidGrantAndTheCodeIsSpent() {
        Account mai = admin("demoted");
        Registered client = registerClient(null);
        String code = consentCode(mai, client.clientId());
        jdbc.update("UPDATE app_user SET role = 'MEMBER' WHERE id = ?", mai.id());

        ResponseEntity<String> first = exchange(client, code, VERIFIER, CALLBACK);
        jdbc.update("UPDATE app_user SET role = 'ADMIN' WHERE id = ?", mai.id());
        ResponseEntity<String> second = exchange(client, code, VERIFIER, CALLBACK);

        assertThat(body(first).get("error")).isEqualTo("invalid_grant");
        assertThat(body(second).get("error")).isEqualTo("invalid_grant");
        assertThat(tokens(mai.id())).isZero();
    }

    @Test
    void clientAuthenticationFollowsBasicThenFormAndPublicClientsSendNoSecret() {
        Account mai = admin("auth");
        Registered confidential = registerClient(null);
        Registered open = registerClient("none");
        Map<String, String> base = new LinkedHashMap<>();
        base.put("grant_type", "authorization_code");
        base.put("code_verifier", VERIFIER);
        base.put("redirect_uri", CALLBACK);

        Map<String, String> basicWins = new LinkedHashMap<>(base);
        basicWins.put("code", consentCode(mai, confidential.clientId()));
        basicWins.put("client_secret", "wrong-form-secret");
        assertThat(postForm("/api/v1/oauth/token", basicWins, confidential.clientId(), confidential.clientSecret(),
                freshAddress()).getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, String> wrongBasic = new LinkedHashMap<>(base);
        wrongBasic.put("code", consentCode(mai, confidential.clientId()));
        ResponseEntity<String> denied = postForm("/api/v1/oauth/token", wrongBasic, confidential.clientId(),
                "wrong-secret", freshAddress());
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(denied.getHeaders().getFirst("WWW-Authenticate")).isEqualTo("Basic realm=\"oauth\"");
        assertThat(body(denied).get("error")).isEqualTo("invalid_client");

        Map<String, String> noSecret = new LinkedHashMap<>(base);
        noSecret.put("code", wrongBasic.get("code"));
        noSecret.put("client_id", confidential.clientId());
        assertThat(postForm("/api/v1/oauth/token", noSecret, null, null, freshAddress()).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        Map<String, String> publicClient = new LinkedHashMap<>(base);
        publicClient.put("code", consentCode(mai, open.clientId()));
        publicClient.put("client_id", open.clientId());
        assertThat(postForm("/api/v1/oauth/token", publicClient, null, null, freshAddress()).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        Map<String, String> publicWithSecret = new LinkedHashMap<>(publicClient);
        publicWithSecret.put("code", consentCode(mai, open.clientId()));
        publicWithSecret.put("client_secret", "anything");
        assertThat(postForm("/api/v1/oauth/token", publicWithSecret, null, null, freshAddress()).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void tokenEndpointErrorsAreOAuthJsonThatNeverEchoTheRequest() {
        Account mai = admin("errors");
        Registered client = registerClient(null);
        String code = consentCode(mai, client.clientId());

        ResponseEntity<String> unsupported = postForm("/api/v1/oauth/token",
                Map.of("grant_type", "client_credentials", "client_id", client.clientId()), null, null, freshAddress());
        assertThat(unsupported.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(body(unsupported).get("error")).isEqualTo("unsupported_grant_type");

        Map<String, String> noVerifier = Map.of("grant_type", "authorization_code", "code", code, "redirect_uri",
                CALLBACK, "client_id", client.clientId(), "client_secret", client.clientSecret());
        ResponseEntity<String> missing = postForm("/api/v1/oauth/token", noVerifier, null, null, freshAddress());
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(body(missing).get("error")).isEqualTo("invalid_request");
        assertThat(missing.getHeaders().getContentType().toString()).startsWith("application/json");
        assertThat(missing.getHeaders().getCacheControl()).contains("no-store");
        assertThat(missing.getBody()).doesNotContain(code).doesNotContain(client.clientSecret());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Forwarded-For", freshAddress());
        ResponseEntity<String> asJson = client().exchange("/api/v1/oauth/token", HttpMethod.POST,
                new HttpEntity<>(Map.of("grant_type", "authorization_code", "code", code), headers), String.class);
        assertThat(asJson.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(body(asJson).get("error")).isEqualTo("invalid_request");
        assertThat(exchange(client, code, VERIFIER, CALLBACK).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void aClientRevokesOnlyTheTokensItWasIssued() {
        Account mai = admin("revoke");
        Registered client = registerClient(null);
        Registered other = registerClient(null);
        String token = body(exchange(client, consentCode(mai, client.clientId()), VERIFIER, CALLBACK))
                .get("access_token").toString();
        String profileToken = "fc_pat_" + OpaqueTokens.generateRawToken();
        jdbc.update("INSERT INTO personal_access_token (id, user_id, name, token_hash, created_at, expires_at)"
                + " VALUES (?, ?, 'profile', ?, now(), now() + interval '30 days')", UUID.randomUUID(), mai.id(),
                OpaqueTokens.hash(profileToken));

        assertThat(revoke(other, token).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(revoke(client, profileToken).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(revoke(client, "fc_pat_doesnotexist").getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(status("/api/v1/auth/me", HttpMethod.GET, token)).isEqualTo(HttpStatus.OK);
        assertThat(status("/api/v1/auth/me", HttpMethod.GET, profileToken)).isEqualTo(HttpStatus.OK);
        assertThat(revoke(client, token).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(status("/api/v1/auth/me", HttpMethod.GET, token)).isEqualTo(HttpStatus.UNAUTHORIZED);
        ResponseEntity<String> bad = postForm("/api/v1/oauth/revoke", Map.of("token", token, "client_id",
                client.clientId(), "client_secret", "wrong"), null, null, freshAddress());
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @Order(1)
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void aRegistrationAndACodeAreIssuedBeforeABackendRestart() {
        Account mai = admin("restart");
        beforeRestart = registerClient(null);
        codeBeforeRestart = consentCode(mai, beforeRestart.clientId());

        assertThat(codeBeforeRestart).hasSize(43);
    }

    @Test
    @Order(2)
    void theClientAndCodeSurviveTheRestartAndTheExchangeSucceeds() {
        assertThat(beforeRestart).isNotNull();

        ResponseEntity<String> response = exchange(beforeRestart, codeBeforeRestart, VERIFIER, CALLBACK);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(response).get("access_token").toString()).startsWith("fc_pat_");
    }
}
