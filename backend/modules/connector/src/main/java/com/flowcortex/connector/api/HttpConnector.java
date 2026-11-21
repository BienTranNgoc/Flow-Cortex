package com.flowcortex.connector.api;

import java.util.UUID;

/** Calls an HTTP API through a connector its member saved; the stored token is injected, never returned. */
public interface HttpConnector {

    /**
     * Makes one request pinned to the connector's base URL. An error status from the API is a result,
     * not a failure.
     *
     * @param memberId the run owner's id, never a caller-chosen one
     * @throws ConnectorException when the member, connector or request is refused, or no answer arrived
     */
    HttpConnectorResponse call(UUID memberId, HttpConnectorRequest request);
}
