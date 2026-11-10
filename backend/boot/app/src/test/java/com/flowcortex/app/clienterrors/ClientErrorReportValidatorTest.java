package com.flowcortex.app.clienterrors;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ClientErrorReportValidatorTest {

    private static final String MINIMAL = "{\"v\":1,\"kind\":\"render\",\"app\":\"member\","
            + "\"name\":\"TypeError\",\"route\":\"/workflows/42\"}";

    private final ClientErrorReportValidator validator = new ClientErrorReportValidator();

    @Test
    void theMinimalValidReportPasses() {
        ClientErrorReportValidator.Result result = validator.validate(MINIMAL);

        assertThat(result.refusal()).isNull();
        assertThat(result.report()).isEqualTo(
                new ClientErrorReport(ClientErrorReport.Kind.RENDER, ClientErrorReport.App.MEMBER,
                        "TypeError", "/workflows/42", null));
    }

    @Test
    void aReportWithALanguagePasses() {
        ClientErrorReportValidator.Result result = validator.validate(
                "{\"v\":1,\"kind\":\"query\",\"app\":\"admin\",\"name\":\"N\",\"route\":\"/\",\"lang\":\"vi-VN\"}");

        assertThat(result.refusal()).isNull();
        assertThat(result.report().lang()).isEqualTo("vi-VN");
        assertThat(result.report().kind()).isEqualTo(ClientErrorReport.Kind.QUERY);
        assertThat(result.report().app()).isEqualTo(ClientErrorReport.App.ADMIN);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidBodies")
    void aReportThatBreaksTheSchemaIsInvalid(String label, String body) {
        ClientErrorReportValidator.Result result = validator.validate(body);

        assertThat(result.report()).isNull();
        assertThat(result.refusal()).isEqualTo(ClientErrorRefusal.INVALID);
        assertThat(result.refusal().code()).isEqualTo("client_error_invalid");
    }

    static Stream<Arguments> invalidBodies() {
        return Stream.of(
                Arguments.of("unknown top-level key", withExtra("\"message\":\"boom\"")),
                Arguments.of("version 2", MINIMAL.replace("\"v\":1", "\"v\":2")),
                Arguments.of("version as text", MINIMAL.replace("\"v\":1", "\"v\":\"1\"")),
                Arguments.of("missing version", MINIMAL.replace("\"v\":1,", "")),
                Arguments.of("kind window", MINIMAL.replace("\"render\"", "\"window\"")),
                Arguments.of("app site", MINIMAL.replace("\"member\"", "\"site\"")),
                Arguments.of("empty name", MINIMAL.replace("\"TypeError\"", "\"\"")),
                Arguments.of("81-character name", MINIMAL.replace("TypeError", "n".repeat(81))),
                Arguments.of("route without leading slash", MINIMAL.replace("/workflows/42", "workflows")),
                Arguments.of("121-character route", MINIMAL.replace("/workflows/42", "/" + "r".repeat(120))),
                Arguments.of("9-character lang", withExtra("\"lang\":\"123456789\"")),
                Arguments.of("number where a string is expected", MINIMAL.replace("\"TypeError\"", "5")),
                Arguments.of("null name", MINIMAL.replace("\"TypeError\"", "null")),
                Arguments.of("null lang", withExtra("\"lang\":null")),
                Arguments.of("object as route", MINIMAL.replace("\"/workflows/42\"", "{}")),
                Arguments.of("a JSON array", "[]"),
                Arguments.of("a JSON string", "\"text\""),
                Arguments.of("JSON null", "null"),
                Arguments.of("empty object", "{}"));
    }

    @Test
    void theLengthBoundsAreInclusive() {
        String body = "{\"v\":1,\"kind\":\"render\",\"app\":\"member\",\"name\":\"" + "n".repeat(80)
                + "\",\"route\":\"/" + "r".repeat(119) + "\",\"lang\":\"12345678\"}";

        assertThat(validator.validate(body).refusal()).isNull();
    }

    @ParameterizedTest
    @MethodSource("unreadableBodies")
    void textThatIsNotJsonIsUnreadable(String body) {
        ClientErrorReportValidator.Result result = validator.validate(body);

        assertThat(result.report()).isNull();
        assertThat(result.refusal()).isEqualTo(ClientErrorRefusal.UNREADABLE);
        assertThat(result.refusal().code()).isEqualTo("client_error_unreadable");
    }

    static Stream<String> unreadableBodies() {
        return Stream.of("", "not json", "{\"v\":1", MINIMAL + " trailing", "{\"v\":1,\"v\":1}");
    }

    private static String withExtra(String member) {
        return MINIMAL.substring(0, MINIMAL.length() - 1) + "," + member + "}";
    }
}
