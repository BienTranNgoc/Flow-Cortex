package com.flowcortex.user.phone;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.user.phone.VietguysAnswer.Kind;
import java.nio.charset.StandardCharsets;
import java.util.OptionalInt;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** HTTP status and body bytes of a carrier answer to a written message → the one result it maps to. */
class VietguysAnswerTest {

    static Stream<Arguments> answers() {
        Stream.Builder<Arguments> rows = Stream.builder();
        rows.add(row(200, "{\"error\":0}", Kind.ACCEPTED));
        rows.add(row(200, "{\"error\":\"0\"}", Kind.ACCEPTED));
        rows.add(row(200, "{\"error\":0,\"msgid\":\"123\"}", Kind.ACCEPTED));
        rows.add(row(200, "{\"error\":1,\"error_code\":-16}", Kind.DUPLICATE));
        rows.add(row(200, "{\"error\":1,\"error_code\":-10}", Kind.NUMBER_REJECTED));
        rows.add(row(200, "{\"error\":1,\"error_code\":\"-11\"}", Kind.NUMBER_REJECTED));
        rows.add(row(401, "{\"error\":1,\"error_code\":-5}", Kind.AUTH_REJECTED));
        rows.add(row(200, "{\"error\":1,\"error_code\":12}", Kind.AUTH_REJECTED));
        for (int code : new int[] {11, -1, -3, -4, -6, -7, -8, -13, -14, -18, -20, -21, -22, -23}) {
            rows.add(row(200, errorCode(code), Kind.CONFIG_REJECTED));
        }
        rows.add(row(200, "{\"error\":1,\"error_code\":-9}", Kind.OUT_OF_CREDIT));
        for (int status : new int[] {200, 500}) {
            rows.add(row(status, "{\"error\":1,\"error_code\":-2}", Kind.UNAVAILABLE));
            rows.add(row(status, "{\"error\":1,\"error_code\":-19}", Kind.UNAVAILABLE));
            rows.add(row(status, "{\"error\":1,\"error_code\":-99}", Kind.UNAVAILABLE));
            rows.add(row(status, "{\"error\":1}", Kind.UNAVAILABLE));
            rows.add(row(status, "{\"error\":1,\"error_code\":\"abc\"}", Kind.UNAVAILABLE));
        }
        for (int status : new int[] {200, 204}) {
            for (String body : new String[] {"<html>", "\"text\"", "[]", "null", ""}) {
                rows.add(row(status, body, Kind.UNCONFIRMED));
            }
        }
        for (String body : new String[] {"", "<html>", "[]"}) {
            rows.add(row(401, body, Kind.AUTH_REJECTED));
        }
        for (int status : new int[] {400, 502, 503}) {
            for (String body : new String[] {"<html>", "\"text\"", "[]", "null", ""}) {
                rows.add(row(status, body, Kind.UNAVAILABLE));
            }
        }
        return rows.build();
    }

    @ParameterizedTest(name = "HTTP {0} {1} -> {2}")
    @MethodSource("answers")
    void classifiesTheAnswer(int status, String body, Kind kind) {
        assertThat(VietguysAnswer.classify(status, body.getBytes(StandardCharsets.UTF_8))).isEqualTo(kind);
    }

    @Test
    void onlyAcceptedDuplicateAndUnconfirmedCountAsSent() {
        assertThat(Kind.ACCEPTED.countsAsSent()).isTrue();
        assertThat(Kind.DUPLICATE.countsAsSent()).isTrue();
        assertThat(Kind.UNCONFIRMED.countsAsSent()).isTrue();
        for (Kind kind : new Kind[] {Kind.NUMBER_REJECTED, Kind.AUTH_REJECTED, Kind.CONFIG_REJECTED,
                Kind.OUT_OF_CREDIT, Kind.UNAVAILABLE}) {
            assertThat(kind.countsAsSent()).as(kind.name()).isFalse();
        }
    }

    @Test
    void eachKindCarriesItsDeliveryOrItsFailureReason() {
        assertThat(Kind.ACCEPTED.delivery()).contains(SmsDelivery.ACCEPTED);
        assertThat(Kind.DUPLICATE.delivery()).contains(SmsDelivery.DUPLICATE);
        assertThat(Kind.UNCONFIRMED.delivery()).contains(SmsDelivery.UNCONFIRMED);
        assertThat(Kind.NUMBER_REJECTED.failure()).contains(SmsSendFailure.Reason.NUMBER_REJECTED);
        for (Kind kind : new Kind[] {Kind.AUTH_REJECTED, Kind.CONFIG_REJECTED, Kind.OUT_OF_CREDIT,
                Kind.UNAVAILABLE}) {
            assertThat(kind.failure()).as(kind.name()).contains(SmsSendFailure.Reason.UNAVAILABLE);
            assertThat(kind.delivery()).as(kind.name()).isEmpty();
        }
        assertThat(Kind.ACCEPTED.failure()).isEmpty();
    }

    @Test
    void theErrorCodeIsReadForTheLogLineOfARejection() {
        assertThat(VietguysAnswer.errorCode(bytes("{\"error\":1,\"error_code\":\"-20\"}"))).isEqualTo(OptionalInt.of(-20));
        assertThat(VietguysAnswer.errorCode(bytes("{\"error\":1}"))).isEmpty();
        assertThat(VietguysAnswer.errorCode(bytes("<html>"))).isEmpty();
        assertThat(VietguysAnswer.errorCode(bytes("[]"))).isEmpty();
    }

    private static Arguments row(int status, String body, Kind kind) {
        return Arguments.of(status, body, kind);
    }

    private static String errorCode(int code) {
        return "{\"error\":1,\"error_code\":" + code + "}";
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
