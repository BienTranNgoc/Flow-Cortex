package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** {@link ConnectorCallConfig}. */
class ConnectorCallConfigTest {

    private static final String URL = "\"url\":\"https://api.notion.com/v1/pages\"";

    private static String config(String... members) {
        return "{" + String.join(",", members) + "}";
    }

    private static String withKey(String key) {
        return config("\"connector\":\"" + key + "\"", URL);
    }

    private static String valid(String extra) {
        return config("\"connector\":\"notion-main\"", URL, extra);
    }

    private static String withUrl(String url) {
        return config("\"connector\":\"notion-main\"", "\"url\":\"" + url + "\"");
    }

    private static void assertRefused(String configJson) {
        assertThat(ConnectorCallConfig.fault(configJson)).isNotBlank();
        assertThat(ConnectorCallConfig.parse(configJson).config()).isNull();
    }

    @Test
    void validPostConfigParses() {
        ConnectorCallConfig.Result result = ConnectorCallConfig.parse(config(
                "\"connector\":\"notion-main\"", "\"method\":\"POST\"", URL,
                "\"headers\":{\"Notion-Version\":\"2022-06-28\"}", "\"bodyFromInput\":true"));

        assertThat(result.fault()).isNull();
        ConnectorCallConfig parsed = result.config();
        assertThat(parsed.connector()).isEqualTo("notion-main");
        assertThat(parsed.method()).isEqualTo("POST");
        assertThat(parsed.url().toString()).isEqualTo("https://api.notion.com/v1/pages");
        assertThat(parsed.headers()).containsExactly(Map.entry("Notion-Version", "2022-06-28"));
        assertThat(parsed.body()).isNull();
        assertThat(parsed.bodyFromInput()).isTrue();
    }

    @Test
    void methodDefaultsToGet() {
        ConnectorCallConfig parsed = ConnectorCallConfig.parse(withKey("notion-main")).config();

        assertThat(parsed.method()).isEqualTo("GET");
        assertThat(parsed.headers()).isEmpty();
        assertThat(parsed.bodyFromInput()).isFalse();
    }

    @Test
    void bodyFromConfigIsKept() {
        ConnectorCallConfig parsed = ConnectorCallConfig.parse(
                valid("\"method\":\"PUT\",\"body\":{\"title\":\"Launch\"}")).config();

        assertThat(parsed.body().get("title").asText()).isEqualTo("Launch");
    }

    @Test
    void bodyFromInputFalseIsNoBody() {
        ConnectorCallConfig.Result result = ConnectorCallConfig.parse(valid("\"bodyFromInput\":false"));

        assertThat(result.fault()).isNull();
        assertThat(result.config().bodyFromInput()).isFalse();
    }

    @Test
    void missingConnectorIsRefused() {
        assertRefused(config(URL));
    }

    @ParameterizedTest
    @ValueSource(strings = {"telegram", "google", "slack", "Notion Main", "a", "1abc", "Notion"})
    void badOrReservedKeyIsRefused(String key) {
        assertRefused(withKey(key));
    }

    @Test
    void keyOfFiftyCharactersPassesAndFiftyOneIsRefused() {
        assertThat(ConnectorCallConfig.fault(withKey("a" + "b".repeat(49)))).isNull();
        assertRefused(withKey("a" + "b".repeat(50)));
    }

    @Test
    void nonTextConnectorIsRefused() {
        assertRefused(config("\"connector\":7", URL));
    }

    @Test
    void missingUrlIsRefused() {
        assertRefused(config("\"connector\":\"notion-main\""));
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://api.notion.com/v1/pages", "/v1/pages", "https:///v1/pages", "https://",
            "ftp://api.notion.com/x", "not a url", "https://api.notion.com/a b"})
    void urlThatIsNotAbsoluteHttpsWithHostIsRefused(String url) {
        assertRefused(withUrl(url));
    }

    @Test
    void nonTextUrlIsRefused() {
        assertRefused(config("\"connector\":\"notion-main\"", "\"url\":12"));
    }

    @Test
    void urlLengthLimitIsTwoThousandFortyEight() {
        String prefix = "https://api.notion.com/";
        String atLimit = prefix + "a".repeat(2048 - prefix.length());

        assertThat(ConnectorCallConfig.fault(withUrl(atLimit))).isNull();
        assertRefused(withUrl(atLimit + "a"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"TRACE", "post", "Get", "CONNECT", "OPTIONS", ""})
    void unknownOrLowerCaseMethodIsRefused(String method) {
        assertRefused(valid("\"method\":\"" + method + "\""));
    }

    @Test
    void nonTextMethodIsRefused() {
        assertRefused(valid("\"method\":1"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "POST", "PUT", "PATCH", "DELETE", "HEAD"})
    void everyListedMethodIsAccepted(String method) {
        assertThat(ConnectorCallConfig.fault(valid("\"method\":\"" + method + "\""))).isNull();
    }

    @Test
    void bodyAndBodyFromInputTogetherAreRefused() {
        assertRefused(valid("\"method\":\"POST\",\"body\":{\"a\":1},\"bodyFromInput\":true"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "HEAD"})
    void bodyOnGetOrHeadIsRefused(String method) {
        assertRefused(valid("\"method\":\"" + method + "\",\"body\":{\"a\":1}"));
        assertRefused(valid("\"method\":\"" + method + "\",\"bodyFromInput\":true"));
    }

    @Test
    void bodyOnDefaultGetIsRefused() {
        assertRefused(valid("\"body\":\"x\""));
    }

    @Test
    void nonBooleanBodyFromInputIsRefused() {
        assertRefused(valid("\"method\":\"POST\",\"bodyFromInput\":\"true\""));
    }

    @Test
    void unknownKeyIsRefused() {
        ConnectorCallConfig.Result result = ConnectorCallConfig.parse(valid("\"methd\":\"POST\""));

        assertThat(result.fault()).contains("methd");
        assertThat(result.config()).isNull();
    }

    @Test
    void headersThatAreNotAStringObjectAreRefused() {
        assertRefused(valid("\"headers\":[]"));
        assertRefused(valid("\"headers\":\"x\""));
        assertRefused(valid("\"headers\":{\"X-Note\":1}"));
        assertRefused(valid("\"headers\":{\"X-Note\":null}"));
    }

    @Test
    void tenHeadersPassAndElevenAreRefused() {
        assertThat(ConnectorCallConfig.fault(valid(headers(10)))).isNull();
        assertRefused(valid(headers(11)));
    }

    private static String headers(int count) {
        List<String> members = IntStream.rangeClosed(1, count).mapToObj(i -> "\"X-Note-" + i + "\":\"v\"").toList();
        return "\"headers\":{" + String.join(",", members) + "}";
    }

    @ParameterizedTest
    @ValueSource(strings = {"X Note", "X:Note", "", "X-Nöte", "X/Note"})
    void headerNameThatIsNotAnHttpTokenIsRefused(String name) {
        assertRefused(valid("\"headers\":{\"" + name + "\":\"v\"}"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Authorization", "authorization", "Proxy-Authorization", "COOKIE", "Cookie", "Host",
            "host", "Content-Length", "Transfer-Encoding", "Connection", "Upgrade", "Expect", "te", "TE",
            "Keep-Alive", "Proxy-Connection"})
    void reservedHeaderNameIsRefusedInAnyCase(String name) {
        ConnectorCallConfig.Result result = ConnectorCallConfig.parse(
                valid("\"headers\":{\"" + name + "\":\"Bearer secret_abc\"}"));

        assertThat(result.config()).isNull();
        assertThat(result.fault()).contains(name).doesNotContain("secret_abc");
    }

    @Test
    void headerValueOf1024PassesAndOf1025IsRefused() {
        assertThat(ConnectorCallConfig.fault(valid("\"headers\":{\"X-Note\":\"" + "v".repeat(1024) + "\"}")))
                .isNull();
        assertRefused(valid("\"headers\":{\"X-Note\":\"" + "v".repeat(1025) + "\"}"));
    }

    @Test
    void headerValueWithLineBreakIsRefusedWithoutEchoingIt() {
        for (String value : List.of("a\\r\\nHost: evil.example", "a\\nb", "a\\rb")) {
            ConnectorCallConfig.Result result =
                    ConnectorCallConfig.parse(valid("\"headers\":{\"X-Note\":\"" + value + "\"}"));

            assertThat(result.config()).isNull();
            assertThat(result.fault()).contains("X-Note").doesNotContain("evil");
        }
    }

    @Test
    void credentialLookingHeaderNameIsRefusedWithoutItsValue() {
        ConnectorCallConfig.Result result =
                ConnectorCallConfig.parse(valid("\"headers\":{\"X-Api-Key\":\"key_123\"}"));

        assertThat(result.config()).isNull();
        assertThat(result.fault()).contains("X-Api-Key").doesNotContain("key_123");
    }

    @ParameterizedTest
    @ValueSource(strings = {"X-Auth-Id", "X-Token", "Client-Secret", "x_api_key", "X-ApiKey", "X-Session-Id",
            "X-Password"})
    void everyCredentialShapedHeaderNameIsRefused(String name) {
        assertRefused(valid("\"headers\":{\"" + name + "\":\"v\"}"));
    }

    @Test
    void credentialLookingQueryParameterIsRefusedWithoutItsValue() {
        ConnectorCallConfig.Result result =
                ConnectorCallConfig.parse(withUrl("https://api.example.com/v1/items?api_key=key_123"));

        assertThat(result.config()).isNull();
        assertThat(result.fault()).contains("api_key").doesNotContain("key_123");
    }

    @Test
    void encodedAndLaterQueryParametersAreChecked() {
        assertRefused(withUrl("https://api.example.com/v1?page=1&access%5Ftoken=x"));
        assertRefused(withUrl("https://api.example.com/v1?token"));
    }

    @Test
    void ordinaryHeaderAndQueryPass() {
        ConnectorCallConfig.Result result = ConnectorCallConfig.parse(config(
                "\"connector\":\"notion-main\"", "\"url\":\"https://api.example.com/v1/items?page_size=10\"",
                "\"headers\":{\"Notion-Version\":\"2022-06-28\"}"));

        assertThat(result.fault()).isNull();
        assertThat(result.config().url().getQuery()).isEqualTo("page_size=10");
    }

    @Test
    void configThatIsNotAJsonObjectIsRefused() {
        assertRefused("[]");
        assertRefused("\"x\"");
        assertRefused("7");
        assertRefused("{not json");
        assertRefused("{} {}");
        assertRefused("");
        assertRefused("   ");
    }

    @Test
    void nullConfigIsRefused() {
        assertThat(ConnectorCallConfig.fault(null)).isNotBlank();
        assertThat(ConnectorCallConfig.parse(null).config()).isNull();
        assertRefused("null");
    }
}
