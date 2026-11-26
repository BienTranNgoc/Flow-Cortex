package com.flowcortex.app.user;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * The MCP consent grant holds a session to the same second-factor rule as creating a token by hand: an admin
 * session that never passed a code gets no authorization code and keeps its step-up proof.
 */
@TotpIt
@TestPropertySource(properties = {
        "flowcortex.auth.mcp-oauth.enabled=true",
        "flowcortex.app.public-url=https://app.nodi.vn/"})
class TotpOAuthGrantIT extends AbstractTotpIT {

    private static final String CALLBACK = "https://claude.ai/api/mcp/auth_callback";
    private static final String CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";
    private static final String AUTHORIZE = "/api/v1/oauth/authorize";

    private String registerClient() {
        Reply registered = post("/api/v1/oauth/register", null,
                Map.of("client_name", "Claude", "redirect_uris", List.of(CALLBACK)));
        assertThat(registered.status()).as("register: %s", registered.body()).isEqualTo(201);
        return registered.text("client_id");
    }

    private String proof(Session session) {
        Reply step = post("/api/v1/me/step-up", session.accessToken(),
                Map.of("password", PASSWORD, "purpose", "PAT_CREATE"));
        assertThat(step.status()).as("step-up: %s", step.body()).isEqualTo(200);
        return step.text("stepUpProof");
    }

    private Reply grant(Session session, String clientId, String proof) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("response_type", "code");
        body.put("client_id", clientId);
        body.put("redirect_uri", CALLBACK);
        body.put("code_challenge", CHALLENGE);
        body.put("code_challenge_method", "S256");
        body.put("scope", "mcp");
        body.put("stepUpProof", proof);
        return post(AUTHORIZE, session.accessToken(), body);
    }

    private Reply check(Session session, String clientId) {
        return get(AUTHORIZE + "/request?response_type=code&client_id=" + clientId + "&code_challenge=" + CHALLENGE
                + "&code_challenge_method=S256&redirect_uri=" + CALLBACK, session.accessToken());
    }

    private int codesOf(UUID userId) {
        return jdbc.queryForObject("SELECT count(*) FROM oauth_authorization_code WHERE user_id = ?", Integer.class,
                userId);
    }

    @Test
    void adminSessionWithoutASecondFactorCannotGrantAndTheProofSurvives() {
        Account root = admin();
        Session passwordOnly = passwordSession(root.email());
        String clientId = registerClient();
        String proof = proof(passwordOnly);

        Reply consent = check(passwordOnly, clientId);
        Reply refused = grant(passwordOnly, clientId, proof);

        assertThat(consent.status()).isEqualTo(200);
        assertThat(consent.body().get("eligible").asBoolean()).isFalse();
        assertThat(refused.status()).as("%s", refused.body()).isEqualTo(403);
        assertThat(refused.text("code")).isEqualTo("PAT_MFA_REQUIRED");
        assertThat(codesOf(root.id())).isZero();

        Enrolment enrolled = enroll(root);

        assertThat(check(enrolled.session(), clientId).body().get("eligible").asBoolean()).isTrue();
        Reply granted = grant(enrolled.session(), clientId, proof);
        assertThat(granted.status()).as("the refusal kept the proof: %s", granted.body()).isEqualTo(200);
        assertThat(granted.text("redirectTo")).startsWith(CALLBACK + "?code=");
        assertThat(codesOf(root.id())).isEqualTo(1);
    }

    @Test
    void switchOffLetsAPasswordOnlySessionGrant() {
        requirement.set(false);
        Account root = admin();
        Session passwordOnly = passwordSession(root.email());
        String clientId = registerClient();

        assertThat(check(passwordOnly, clientId).body().get("eligible").asBoolean()).isTrue();
        Reply granted = grant(passwordOnly, clientId, proof(passwordOnly));

        assertThat(granted.status()).as("%s", granted.body()).isEqualTo(200);
        assertThat(codesOf(root.id())).isEqualTo(1);
    }
}
