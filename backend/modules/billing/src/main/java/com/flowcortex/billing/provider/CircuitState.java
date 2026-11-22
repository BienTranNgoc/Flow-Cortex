package com.flowcortex.billing.provider;

/** The three states of a provider circuit; the value is the tag metrics carry. */
public enum CircuitState {
    CLOSED("closed"),
    OPEN("open"),
    HALF_OPEN("half_open");

    private final String value;

    CircuitState(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
