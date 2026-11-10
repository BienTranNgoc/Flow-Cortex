package com.flowcortex.user.preferences;

import com.fasterxml.jackson.annotation.JsonValue;

/** Whether a member's statistics are shown on their profile. */
public enum StatsVisibility implements WireValue {
    SHOW("show"),
    HIDE("hide");

    private final String wire;

    StatsVisibility(String wire) {
        this.wire = wire;
    }

    @Override
    @JsonValue
    public String wire() {
        return wire;
    }
}
