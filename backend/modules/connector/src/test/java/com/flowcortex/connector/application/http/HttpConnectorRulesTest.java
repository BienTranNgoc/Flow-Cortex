package com.flowcortex.connector.application.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowcortex.connector.api.ConnectorException;
import com.flowcortex.connector.api.ConnectorException.Reason;
import com.flowcortex.connector.api.HttpConnectorRequest;
import com.flowcortex.connector.application.http.HttpConnectorRules.Capped;
import com.flowcortex.connector.application.http.HttpConnectorRules.Connection;
import com.flowcortex.connector.application.http.HttpConnectorRules.Prepared;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;

class HttpConnectorRulesTest {

    private static final String BASE = "https://api.example.com/v2";
    private static final Connection BEARER = new Connection(BASE, "bearer", null, null);
    private static final Connection HEADER = new Connection(BASE, "header", "X-Api-Key", null);
    private static final Connection QUERY = new Connection(BASE, "query", null, "api_key");

    private static HttpSecret.Decoded secret(String token) {
        return new HttpSecret.Decoded(token, Map.of());
    }

    private static HttpConnectorRequest call(String method, String url, Map<String, String> headers, Object body) {
        return new HttpConnectorRequest("k", method, url, headers, body);
    }

    private static void assertInvalid(Runnable action, String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ConnectorException.class, ex -> {
            assertThat(ex.reason()).isEqualTo(Reason.INVALID_INPUT);
            assertThat(ex.detail().code()).isEqualTo(code);
        });
    }

    @Test
    void aKeyIsTrimmedAndLowerCased() {
        assertThat(HttpConnectorRules.normalizeKey("  Notion-Main ")).contains("notion-main");
    }

    @ParameterizedTest
    @ValueSource(strings = {"telegram", "Google", "slack", "1abc", "a", "-ab", "has space"})
    void aReservedOrMalformedKeyIsRefused(String key) {
        assertThat(HttpConnectorRules.normalizeKey(key)).isEmpty();
    }

    @Test
    void aKeyOfFiftyOneCharactersIsRefusedAndFiftyIsAccepted() {
        assertThat(HttpConnectorRules.normalizeKey("a".repeat(51))).isEmpty();
        assertThat(HttpConnectorRules.normalizeKey("a".repeat(50))).isPresent();
        assertThat(HttpConnectorRules.normalizeKey(null)).isEmpty();
    }

    @Test
    void eachAuthModeIsAcceptedWithItsRequiredName() {
        assertThat(HttpConnectorRules.connection(BASE + "/", "bearer", "t", null, null))
                .isEqualTo(new Connection(BASE, "bearer", null, null));
        assertThat(HttpConnectorRules.connection(BASE, "header", "t", " X-Api-Key ", null))
                .isEqualTo(HEADER);
        assertThat(HttpConnectorRules.connection(BASE, "query", "t", null, "api_key")).isEqualTo(QUERY);
    }

    @Test
    void aMissingTokenOrNameNamesTheMissingThing() {
        assertInvalid(() -> HttpConnectorRules.connection(BASE, "bearer", " ", null, null),
                "connector.http.tokenRequired");
        assertInvalid(() -> HttpConnectorRules.connection(BASE, "bearer", null, null, null),
                "connector.http.tokenRequired");
        assertInvalid(() -> HttpConnectorRules.connection(BASE, "header", "t", " ", null),
                "connector.http.headerNameRequired");
        assertInvalid(() -> HttpConnectorRules.connection(BASE, "query", "t", null, null),
                "connector.http.paramNameRequired");
    }

    @Test
    void aTokenThatCannotBeSentAsAHeaderIsRefused() {
        assertInvalid(() -> HttpConnectorRules.connection(BASE, "bearer", "a\r\nX: y", null, null),
                "connector.http.tokenRequired");
    }

    @Test
    void aTransportHeaderCannotCarryTheToken() {
        assertInvalid(() -> HttpConnectorRules.connection(BASE, "header", "t", "Host", null),
                "connector.http.headerNotAllowed");
        assertInvalid(() -> HttpConnectorRules.connection(BASE, "header", "t", "bad name", null),
                "connector.http.headerNotAllowed");
    }

    @Test
    void anInvalidBaseUrlIsRefused() {
        assertInvalid(() -> HttpConnectorRules.connection("http://api.example.com", "bearer", "t", null, null),
                "connector.http.baseUrlInvalid");
    }

    @Test
    void noneIsNotAnAuthMode() {
        assertThatThrownBy(() -> HttpConnectorRules.connection(BASE, "none", "t", null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void elevenExtraHeadersAreRefusedAndTenAccepted() {
        Map<String, String> ten = new LinkedHashMap<>();
        for (int i = 0; i < 10; i++) {
            ten.put("X-H" + i, "v");
        }
        assertThat(HttpConnectorRules.extraHeaders(ten, BEARER)).hasSize(10);
        ten.put("X-H10", "v");
        assertInvalid(() -> HttpConnectorRules.extraHeaders(ten, BEARER), "connector.http.headerNotAllowed");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Authorization", "authorization", "Proxy-Authorization", "Cookie", "Host",
            "Content-Length", "Transfer-Encoding", "Connection", "Upgrade", "Expect", "TE", "Keep-Alive",
            "Proxy-Connection", "has space", "x-api-key", ""})
    void aRefusedHeaderNameCannotBeAnExtraHeader(String name) {
        assertInvalid(() -> HttpConnectorRules.extraHeaders(Map.of(name, "v"), HEADER),
                "connector.http.headerNotAllowed");
    }

    @Test
    void anExtraHeaderValueMustBeOneShortLine() {
        assertInvalid(() -> HttpConnectorRules.extraHeaders(Map.of("X-A", "a\nb"), BEARER),
                "connector.http.headerNotAllowed");
        assertInvalid(() -> HttpConnectorRules.extraHeaders(Map.of("X-A", "a\rb"), BEARER),
                "connector.http.headerNotAllowed");
        assertInvalid(() -> HttpConnectorRules.extraHeaders(Map.of("X-A", "a".repeat(1025)), BEARER),
                "connector.http.headerNotAllowed");
        assertThat(HttpConnectorRules.extraHeaders(Map.of("X-A", "a".repeat(1024)), BEARER)).hasSize(1);
    }

    @Test
    void theSecretRoundTripsTheTokenAndExtraHeaderValues() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Notion-Version", "2022-06-28");
        headers.put("X-Secret", "s3cr3t");

        HttpSecret.Decoded decoded = HttpSecret.decode(HttpSecret.encode("secret_abc", headers));

        assertThat(decoded.token()).isEqualTo("secret_abc");
        assertThat(decoded.extraHeaders()).containsExactlyEntriesOf(headers);
        assertThat(decoded.toString()).doesNotContain("secret_abc").doesNotContain("s3cr3t");
    }

    @Test
    void anUnreadablePayloadIsACredentialProblem() {
        assertThatThrownBy(() -> HttpSecret.decode("not json"))
                .isInstanceOfSatisfying(ConnectorException.class,
                        ex -> assertThat(ex.reason()).isEqualTo(Reason.CREDENTIAL_UNREADABLE));
        assertThatThrownBy(() -> HttpSecret.decode("{\"extraHeaders\":{}}"))
                .isInstanceOfSatisfying(ConnectorException.class,
                        ex -> assertThat(ex.reason()).isEqualTo(Reason.CREDENTIAL_UNREADABLE));
    }

    @Test
    void theSavedMetadataHoldsHeaderNamesOnly() {
        Map<String, String> headers = Map.of("Notion-Version", "2022-06-28");

        Map<String, Object> metadata = HttpConnectorRules.metadata("Notion", BEARER, headers);

        assertThat(metadata).containsEntry("kind", "http").containsEntry("label", "Notion")
                .containsEntry("authType", "bearer").containsEntry("baseUrl", BASE)
                .containsEntry("extraHeaderNames", List.of("Notion-Version"));
        assertThat(metadata.toString()).doesNotContain("2022-06-28");
    }

    @Test
    void theLabelDefaultsToTheKey() {
        assertThat(HttpConnectorRules.label(null, "notion")).isEqualTo("notion");
        assertThat(HttpConnectorRules.label("  ", "notion")).isEqualTo("notion");
        assertThat(HttpConnectorRules.label(" Notion ", "notion")).isEqualTo("Notion");
    }

    @Test
    void bearerInjectsAnAuthorizationHeader() {
        Prepared prepared = HttpConnectorRules.prepare(BEARER, secret("tok"),
                call(null, BASE + "/items", null, null));

        assertThat(prepared.headers()).containsEntry("Authorization", "Bearer tok");
        assertThat(prepared.method()).isEqualTo(HttpMethod.GET);
        assertThat(prepared.uri()).hasToString(BASE + "/items");
    }

    @Test
    void headerModeInjectsTheNamedHeaderAndKeepsExtraHeaders() {
        HttpSecret.Decoded secret = new HttpSecret.Decoded("tok", Map.of("X-Secret", "s"));

        Prepared prepared = HttpConnectorRules.prepare(HEADER, secret,
                call("GET", BASE + "/items", Map.of("Accept", "application/json"), null));

        assertThat(prepared.headers()).containsEntry("X-Api-Key", "tok").containsEntry("X-Secret", "s")
                .containsEntry("Accept", "application/json");
    }

    @Test
    void queryModeAppendsTheEncodedParameter() {
        Prepared withQuery = HttpConnectorRules.prepare(QUERY, secret("a b&c"),
                call("GET", "https://api.example.com/v2/items?page=2", null, null));
        Prepared withoutQuery = HttpConnectorRules.prepare(QUERY, secret("a b&c"),
                call("GET", "https://api.example.com/v2/items", null, null));
        Prepared trailingAmpersand = HttpConnectorRules.prepare(QUERY, secret("t"),
                call("GET", "https://api.example.com/v2/items?page=2&", null, null));

        assertThat(withQuery.uri()).hasToString("https://api.example.com/v2/items?page=2&api_key=a%20b%26c");
        assertThat(withoutQuery.uri()).hasToString("https://api.example.com/v2/items?api_key=a%20b%26c");
        assertThat(trailingAmpersand.uri()).hasToString("https://api.example.com/v2/items?page=2&api_key=t");
        assertThat(withQuery.headers()).doesNotContainKey("Authorization");
    }

    @Test
    void aUrlAlreadyCarryingTheAuthParameterIsRefused() {
        assertInvalid(() -> HttpConnectorRules.prepare(QUERY, secret("t"),
                call("GET", "https://api.example.com/v2/items?api_key=x", null, null)),
                "connector.http.headerNotAllowed");
        assertInvalid(() -> HttpConnectorRules.prepare(QUERY, secret("t"),
                call("GET", "https://api.example.com/v2/items?page=1&api%5Fkey=x", null, null)),
                "connector.http.headerNotAllowed");
    }

    @Test
    void aCallerCannotReplaceTheCredentialOrSteerTheTransport() {
        assertInvalid(() -> HttpConnectorRules.prepare(BEARER, secret("t"),
                call("GET", BASE + "/x", Map.of("Authorization", "Bearer other"), null)),
                "connector.http.headerNotAllowed");
        assertInvalid(() -> HttpConnectorRules.prepare(HEADER, secret("t"),
                call("GET", BASE + "/x", Map.of("x-api-key", "other"), null)),
                "connector.http.headerNotAllowed");
        assertInvalid(() -> HttpConnectorRules.prepare(BEARER, secret("t"),
                call("GET", BASE + "/x", Map.of("Host", "evil.com"), null)),
                "connector.http.headerNotAllowed");
        assertInvalid(() -> HttpConnectorRules.prepare(BEARER, new HttpSecret.Decoded("t", Map.of("X-S", "s")),
                call("GET", BASE + "/x", Map.of("x-s", "other"), null)),
                "connector.http.headerNotAllowed");
        assertInvalid(() -> HttpConnectorRules.prepare(BEARER, secret("t"),
                call("GET", BASE + "/x", Map.of("X-A", "a\r\nX-B: b"), null)),
                "connector.http.headerNotAllowed");
    }

    @Test
    void aUrlOutsideTheBaseIsRefusedBeforeAnythingIsInjected() {
        assertThatThrownBy(() -> HttpConnectorRules.prepare(BEARER, secret("t"),
                call("GET", "https://api.example.com.evil.com/v2/x", null, null)))
                .isInstanceOfSatisfying(ConnectorException.class, ex -> {
                    assertThat(ex.reason()).isEqualTo(Reason.OUT_OF_BASE_URL);
                    assertThat(ex.detail().code()).isEqualTo("connector.http.urlOutsideBaseUrl");
                });
        assertThatThrownBy(() -> HttpConnectorRules.prepare(BEARER, secret("t"), call("GET", null, null, null)))
                .isInstanceOfSatisfying(ConnectorException.class,
                        ex -> assertThat(ex.reason()).isEqualTo(Reason.OUT_OF_BASE_URL));
    }

    @Test
    void theMethodDefaultsToGetAndAcceptsLowerCase() {
        assertThat(HttpConnectorRules.method(null)).isEqualTo(HttpMethod.GET);
        assertThat(HttpConnectorRules.method("")).isEqualTo(HttpMethod.GET);
        assertThat(HttpConnectorRules.method("patch")).isEqualTo(HttpMethod.PATCH);
        assertThat(HttpConnectorRules.method("HEAD")).isEqualTo(HttpMethod.HEAD);
    }

    @Test
    void anUnsupportedMethodIsRefused() {
        assertInvalid(() -> HttpConnectorRules.method("TRACE"), "connector.http.methodInvalid");
        assertInvalid(() -> HttpConnectorRules.prepare(BEARER, secret("t"), call("TRACE", BASE + "/x", null, null)),
                "connector.http.methodInvalid");
    }

    @Test
    void aBodyIsSentForWritingMethodsOnly() {
        for (String method : List.of("POST", "PUT", "PATCH", "DELETE")) {
            assertThat(HttpConnectorRules.prepare(BEARER, secret("t"), call(method, BASE + "/x", null, "b")).body())
                    .isNotNull();
        }
        for (String method : List.of("GET", "HEAD")) {
            assertThat(HttpConnectorRules.prepare(BEARER, secret("t"), call(method, BASE + "/x", null, "b")).body())
                    .isNull();
        }
    }

    @Test
    void aStringBodyGoesAsIsAndAnObjectBodyAsJson() {
        Prepared text = HttpConnectorRules.prepare(BEARER, secret("t"), call("POST", BASE + "/x", null, "a=b"));
        Prepared json = HttpConnectorRules.prepare(BEARER, secret("t"),
                call("POST", BASE + "/x", null, Map.of("title", "x")));

        assertThat(new String(text.body(), StandardCharsets.UTF_8)).isEqualTo("a=b");
        assertThat(new String(json.body(), StandardCharsets.UTF_8)).isEqualTo("{\"title\":\"x\"}");
        assertThat(json.headers()).containsEntry("Content-Type", "application/json");
    }

    @Test
    void aCallerContentTypeWinsOverTheDefault() {
        Prepared prepared = HttpConnectorRules.prepare(BEARER, secret("t"),
                call("POST", BASE + "/x", Map.of("content-type", "text/plain"), "hello"));

        assertThat(prepared.headers()).containsEntry("content-type", "text/plain")
                .doesNotContainKey("Content-Type");
    }

    @Test
    void aSavedExtraContentTypeSurvivesAnObjectBody() {
        HttpSecret.Decoded saved = new HttpSecret.Decoded("t", Map.of("content-type", "application/vnd.api+json"));

        Prepared prepared = HttpConnectorRules.prepare(BEARER, saved,
                call("POST", BASE + "/x", null, Map.of("title", "x")));

        assertThat(prepared.headers()).containsEntry("content-type", "application/vnd.api+json")
                .doesNotContainKey("Content-Type");
    }

    @Test
    void noBodyMeansNoContentType() {
        assertThat(HttpConnectorRules.prepare(BEARER, secret("t"), call("POST", BASE + "/x", null, null)).headers())
                .doesNotContainKey("Content-Type");
    }

    @Test
    void theBodyIsCutAtOneHundredThousandCharacters() {
        Capped under = HttpConnectorRules.cap("a".repeat(99_999), false);
        Capped exact = HttpConnectorRules.cap("a".repeat(100_000), false);
        Capped over = HttpConnectorRules.cap("a".repeat(100_001), false);

        assertThat(under.truncated()).isFalse();
        assertThat(exact.truncated()).isFalse();
        assertThat(exact.text()).hasSize(100_000);
        assertThat(over.truncated()).isTrue();
        assertThat(over.text()).hasSize(100_000);
        assertThat(HttpConnectorRules.cap("x", true).truncated()).isTrue();
        assertThat(HttpConnectorRules.cap(null, false).text()).isEmpty();
    }
}
