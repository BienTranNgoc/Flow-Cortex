package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.connector.api.ConnectorException;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Every connector failure reason ends a {@code connector.call} step with one code and a fixed message. */
class ConnectorCallFailuresTest {

    private static final Map<ConnectorException.Reason, String> CODES = Map.of(
            ConnectorException.Reason.SUSPENDED, "connector_member_suspended",
            ConnectorException.Reason.TURNED_OFF, "connector_turned_off",
            ConnectorException.Reason.NOT_CONNECTED, "connector_not_connected",
            ConnectorException.Reason.USAGE_DISABLED, "connector_usage_disabled",
            ConnectorException.Reason.CREDENTIAL_UNREADABLE, "connector_reconnect_needed",
            ConnectorException.Reason.OUT_OF_BASE_URL, "connector_url_outside_base_url",
            ConnectorException.Reason.INVALID_INPUT, "connector_invalid_request",
            ConnectorException.Reason.UPSTREAM_UNAVAILABLE, "connector_upstream_unavailable",
            ConnectorException.Reason.REFUSED, "connector_refused",
            ConnectorException.Reason.RATE_LIMITED, "connector_rate_limited");

    @Test
    void everyReasonMapsToItsCode() {
        for (ConnectorException.Reason reason : ConnectorException.Reason.values()) {
            assertThat(CODES).as("a code is expected for %s", reason).containsKey(reason);
            assertThat(ConnectorCallFailures.of(reason, "notion-main").code()).as(reason.name())
                    .isEqualTo(CODES.get(reason));
        }
    }

    @Test
    void aReasonWithoutAnEntryInTheTableFailsHere() {
        assertThat(CODES.keySet()).containsExactlyInAnyOrder(ConnectorException.Reason.values());
    }

    @Test
    void theNotConnectedMessageNamesTheConnectorKey() {
        assertThat(ConnectorCallFailures.of(ConnectorException.Reason.NOT_CONNECTED, "notion-main").getMessage())
                .contains("notion-main");
    }

    @Test
    void anErrorStatusNamesOnlyTheStatus() {
        StepFailedException failure = ConnectorCallFailures.upstreamStatus(503);

        assertThat(failure.code()).isEqualTo("connector_upstream_error");
        assertThat(failure.getMessage()).isEqualTo("The connected service answered HTTP 503.");
    }

    @Test
    void anUnexpectedFailureIsUpstreamUnavailableWithAFixedMessage() {
        StepFailedException failure = ConnectorCallFailures.unexpected();

        assertThat(failure.code()).isEqualTo("connector_upstream_unavailable");
        assertThat(failure.getMessage()).isEqualTo(
                ConnectorCallFailures.of(ConnectorException.Reason.UPSTREAM_UNAVAILABLE, "notion-main").getMessage());
    }

    @Test
    void noMappedMessageCarriesTheExceptionTextOrAUrl() {
        String leaky = "https://api.example.com/x?api_key=tok-123";
        for (ConnectorException.Reason reason : ConnectorException.Reason.values()) {
            ConnectorException thrown = new ConnectorException(reason,
                    new com.flowcortex.shared.web.LocalizedMessage(leaky));

            StepFailedException failure = ConnectorCallFailures.of(thrown, "notion-main");

            assertThat(failure.getMessage()).as(reason.name()).doesNotContain("tok-123").doesNotContain("https://");
            assertThat(failure.code()).isEqualTo(CODES.get(reason));
        }
    }
}
