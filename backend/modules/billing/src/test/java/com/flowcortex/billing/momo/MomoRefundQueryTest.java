package com.flowcortex.billing.momo;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.billing.provider.RefundQueryOutcome;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class MomoRefundQueryTest {

    private static final String ORDER = "fc_0123456789abcdef0123456789abcdef";
    private static final String OTHER = "fc_ffffffffffffffffffffffffffffffff";
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static JsonNode json(String text) {
        return MAPPER.readTree(text);
    }

    @Test
    void aSuccessfulQueryWithOurSuccessfulEntryIsSettled() {
        JsonNode answer = json("""
                {"resultCode":0,"refundTrans":[{"orderId":"%s","transId":123,"amount":20000,"resultCode":0}]}"""
                .formatted(ORDER));

        assertThat(MomoRefundQuery.interpret(answer, ORDER))
                .isEqualTo(new RefundQueryOutcome.Settled("123", 20000, "0"));
    }

    @Test
    void aSoleEntryNamingAnotherOrderIsNotOurs() {
        JsonNode answer = json("""
                {"resultCode":0,"refundTrans":[{"orderId":"%s","transId":9,"amount":5000,"resultCode":0}]}"""
                .formatted(OTHER));

        assertThat(MomoRefundQuery.interpret(answer, ORDER)).isEqualTo(new RefundQueryOutcome.Unknown("0"));
    }

    @Test
    void aSoleEntryWithoutAnOrderIdIsUsed() {
        JsonNode answer = json("""
                {"resultCode":0,"refundTrans":[{"transId":9,"amount":5000,"resultCode":0}]}""");

        assertThat(MomoRefundQuery.interpret(answer, ORDER)).isEqualTo(new RefundQueryOutcome.Settled("9", 5000, "0"));
    }

    @Test
    void severalEntriesWithoutOursAreNotGuessedAt() {
        JsonNode answer = json("""
                {"resultCode":0,"refundTrans":[{"orderId":"%s","transId":9,"amount":5000,"resultCode":0},
                {"orderId":"%s","transId":8,"amount":5000,"resultCode":0}]}""".formatted(OTHER, OTHER + "0"));

        assertThat(MomoRefundQuery.interpret(answer, ORDER)).isEqualTo(new RefundQueryOutcome.Unknown("0"));
    }

    @Test
    void code1080OnTheEntryOrTheQueryIsStillPending() {
        JsonNode entry = json("""
                {"resultCode":0,"refundTrans":[{"orderId":"%s","resultCode":1080}]}""".formatted(ORDER));
        JsonNode query = json("{\"resultCode\":1080}");

        assertThat(MomoRefundQuery.interpret(entry, ORDER)).isEqualTo(new RefundQueryOutcome.StillPending("0"));
        assertThat(MomoRefundQuery.interpret(query, ORDER)).isEqualTo(new RefundQueryOutcome.StillPending("1080"));
    }

    @Test
    void everythingElseIsUnknown() {
        assertThat(MomoRefundQuery.interpret(json("{\"resultCode\":0}"), ORDER))
                .isEqualTo(new RefundQueryOutcome.Unknown("0"));
        assertThat(MomoRefundQuery.interpret(json("{\"errorCode\":1001}"), ORDER))
                .isEqualTo(new RefundQueryOutcome.Unknown("1001"));
        assertThat(MomoRefundQuery.interpret(null, ORDER)).isEqualTo(new RefundQueryOutcome.Unknown(null));
    }

    @Test
    void aSuccessfulEntryWithoutAnAmountIsNotSettled() {
        JsonNode answer = json("""
                {"resultCode":0,"refundTrans":[{"orderId":"%s","transId":1,"resultCode":0}]}""".formatted(ORDER));

        assertThat(MomoRefundQuery.interpret(answer, ORDER)).isEqualTo(new RefundQueryOutcome.Unknown("0"));
    }
}
