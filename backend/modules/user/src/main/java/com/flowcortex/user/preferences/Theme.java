package com.flowcortex.user.preferences;

import com.fasterxml.jackson.annotation.JsonValue;

/** The member app colour scheme. */
public enum Theme implements WireValue {
    LIGHT("light"),
    DARK("dark");

    private final String wire;

    Theme(String wire) {
        this.wire = wire;
    }

    @Override
    @JsonValue
    public String wire() {
        return wire;
    }
}
