package com.flowcortex.connector.api;

/** The API's answer: {@code ok} is a 2xx status, {@code body} is text cut at 100,000 characters. */
public record HttpConnectorResponse(int status, boolean ok, String contentType, String body, boolean truncated) {
}
