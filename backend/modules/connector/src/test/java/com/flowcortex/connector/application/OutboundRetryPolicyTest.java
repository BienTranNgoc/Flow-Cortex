package com.flowcortex.connector.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class OutboundRetryPolicyTest {

    private static Throwable fromChain(JsonNode chain) throws Exception {
        List<JsonNode> links = new ArrayList<>();
        chain.forEach(links::add);
        Throwable cause = null;
        for (int i = links.size() - 1; i >= 0; i--) {
            Throwable link = instantiate(links.get(i).path("class").asString(), links.get(i).path("message"));
            if (cause != null) {
                link.initCause(cause);
            }
            cause = link;
        }
        return new ResourceAccessException("I/O error", (IOException) cause);
    }

    private static Throwable instantiate(String type, JsonNode message) throws Exception {
        Class<?> clazz = Class.forName(type);
        String text = message.isNull() ? null : message.asString();
        try {
            Constructor<?> withMessage = clazz.getConstructor(String.class);
            return (Throwable) withMessage.newInstance(text);
        } catch (NoSuchMethodException ex) {
            return (Throwable) clazz.getConstructor().newInstance();
        }
    }

    @Test
    void everyRecordedProxyFailureIsClassifiedAsRecorded() throws Exception {
        JsonNode fixture;
        try (InputStream in = getClass().getResourceAsStream("/outbound/egress-proxy-failures.json")) {
            fixture = JsonMapper.builder().build().readTree(in);
        }
        assertThat(fixture.path("rows").size()).isGreaterThanOrEqualTo(5);

        for (JsonNode row : fixture.path("rows")) {
            Throwable thrown = fromChain(row.path("chain"));
            assertThat(OutboundFailurePhase.classify(thrown))
                    .as(row.path("name").asString())
                    .isEqualTo(OutboundFailurePhase.valueOf(row.path("expectedPhase").asString()));
        }
    }

    @Test
    void connectFailuresAreConnectPhase() {
        assertThat(OutboundFailurePhase.classify(new ConnectException("refused")))
                .isEqualTo(OutboundFailurePhase.CONNECT_PHASE);
        assertThat(OutboundFailurePhase.classify(new UnknownHostException("h")))
                .isEqualTo(OutboundFailurePhase.CONNECT_PHASE);
        assertThat(OutboundFailurePhase.classify(new HttpConnectTimeoutException("HTTP connect timed out")))
                .isEqualTo(OutboundFailurePhase.CONNECT_PHASE);
    }

    @Test
    void readTimeoutAndResetAfterWriteAreAfterWrite() {
        assertThat(OutboundFailurePhase.classify(new HttpTimeoutException("request timed out")))
                .isEqualTo(OutboundFailurePhase.AFTER_WRITE);
        assertThat(OutboundFailurePhase.classify(new IOException("Connection reset")))
                .isEqualTo(OutboundFailurePhase.AFTER_WRITE);
    }

    @Test
    void connectPhaseIsRetriedOnceAndNeverTwice() {
        assertThat(OutboundRetryPolicy.retry(OutboundFailurePhase.CONNECT_PHASE, 1)).isTrue();
        assertThat(OutboundRetryPolicy.retry(OutboundFailurePhase.CONNECT_PHASE, 2)).isFalse();
    }

    @Test
    void afterWriteIsNeverRetried() {
        assertThat(OutboundRetryPolicy.retry(OutboundFailurePhase.AFTER_WRITE, 1)).isFalse();
    }
}
