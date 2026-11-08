package com.flowcortex.space.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Spaces kill switch, read once at startup; absent means off. */
@Component
class SpaceSwitch {

    private final boolean enabled;

    SpaceSwitch(@Value("${flowcortex.spaces.enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    boolean enabled() {
        return enabled;
    }
}
