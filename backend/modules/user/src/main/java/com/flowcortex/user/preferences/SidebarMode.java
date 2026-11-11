package com.flowcortex.user.preferences;

import com.fasterxml.jackson.annotation.JsonValue;

/** How the member app sidebar is shown. */
public enum SidebarMode implements WireValue {
    PINNED("pinned"),
    RAIL("rail");

    private final String wire;

    SidebarMode(String wire) {
        this.wire = wire;
    }

    @Override
    @JsonValue
    public String wire() {
        return wire;
    }
}
