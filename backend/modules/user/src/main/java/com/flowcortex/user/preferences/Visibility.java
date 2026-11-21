package com.flowcortex.user.preferences;

import com.fasterxml.jackson.annotation.JsonValue;

/** Who may see a part of a member's profile or reach them. */
public enum Visibility implements WireValue {
    EVERYONE("everyone"),
    FOLLOWERS("followers"),
    ONLY_ME("only_me");

    private final String wire;

    Visibility(String wire) {
        this.wire = wire;
    }

    @Override
    @JsonValue
    public String wire() {
        return wire;
    }
}
