package com.flowcortex.entity.domain;

/** A refused input: the request field it belongs to and the message key {@code entity.<field>.<reason>}. Never the value. */
public record RuleViolation(String field, String messageKey) {
}
