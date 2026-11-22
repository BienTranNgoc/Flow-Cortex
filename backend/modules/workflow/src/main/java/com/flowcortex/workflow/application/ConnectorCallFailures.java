package com.flowcortex.workflow.application;

import com.flowcortex.connector.api.ConnectorException;
import com.flowcortex.connector.api.ConnectorException.Reason;
import java.util.EnumMap;
import java.util.Map;

/**
 * The failures of a {@code connector.call} step: one stable code and one fixed sentence per reason the
 * connector reports. A sentence names at most the connector key, never the exception's own text, the
 * request URL or anything the third party said.
 */
final class ConnectorCallFailures {

    static final String INVALID_REQUEST = "connector_invalid_request";

    private record Failure(String code, String messageTemplate) {
    }

    private static final Map<Reason, Failure> BY_REASON = new EnumMap<>(Reason.class);

    static {
        BY_REASON.put(Reason.SUSPENDED, new Failure("connector_member_suspended",
                "Your account is suspended, so connected services cannot be called."));
        BY_REASON.put(Reason.TURNED_OFF, new Failure("connector_turned_off",
                "Connected services are switched off right now. Try running again later."));
        BY_REASON.put(Reason.NOT_CONNECTED, new Failure("connector_not_connected",
                "No connector named '%s' is saved. Save it under Connectors, then run again."));
        BY_REASON.put(Reason.USAGE_DISABLED, new Failure("connector_usage_disabled",
                "Connector '%s' is switched off for workflows. Turn it on under Connectors, then run again."));
        BY_REASON.put(Reason.CREDENTIAL_UNREADABLE, new Failure("connector_reconnect_needed",
                "The saved credential of connector '%s' cannot be read. Reconnect it under Connectors."));
        BY_REASON.put(Reason.OUT_OF_BASE_URL, new Failure("connector_url_outside_base_url",
                "The step's URL is outside the base URL of connector '%s'."));
        BY_REASON.put(Reason.INVALID_INPUT, new Failure(INVALID_REQUEST,
                "The request to the connected service is not valid. Check the step's method, URL and body."));
        BY_REASON.put(Reason.UPSTREAM_UNAVAILABLE, new Failure("connector_upstream_unavailable",
                "The connected service could not be reached. Try running again."));
        BY_REASON.put(Reason.REFUSED, new Failure("connector_refused",
                "The connected service refused the request."));
        BY_REASON.put(Reason.RATE_LIMITED, new Failure("connector_rate_limited",
                "The connected service asked to slow down. Try running again later."));
    }

    private ConnectorCallFailures() {
    }

    static StepFailedException of(Reason reason, String connectorKey) {
        Failure failure = BY_REASON.get(reason);
        if (failure == null) {
            throw new IllegalStateException("No failure mapped for connector reason " + reason);
        }
        return new StepFailedException(failure.code(), failure.messageTemplate().formatted(connectorKey));
    }

    static StepFailedException of(ConnectorException exception, String connectorKey) {
        return of(exception.reason(), connectorKey);
    }

    /** The API answered with a status of 500 or above. */
    static StepFailedException upstreamStatus(int status) {
        return new StepFailedException("connector_upstream_error",
                "The connected service answered HTTP " + status + ".");
    }

    /** Anything the connector call threw that is not a {@link ConnectorException}. */
    static StepFailedException unexpected() {
        return of(Reason.UPSTREAM_UNAVAILABLE, null);
    }

    static StepFailedException invalidConfig() {
        return new StepFailedException(INVALID_REQUEST,
                "The step's configuration is not valid. Edit the step and save it again.");
    }

    static StepFailedException emptyBody() {
        return new StepFailedException(INVALID_REQUEST, "The step has no input to send as its body.");
    }

    static StepFailedException runNotFound() {
        return new StepFailedException(INVALID_REQUEST, "The run of this step could not be found.");
    }
}
