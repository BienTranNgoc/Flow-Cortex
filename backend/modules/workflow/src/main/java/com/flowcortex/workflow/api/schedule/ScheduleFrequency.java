package com.flowcortex.workflow.api.schedule;

import java.util.Arrays;

/** How often a schedule fires; {@link #wire()} is the lowercase value stored and sent. */
public enum ScheduleFrequency {
    DAILY("daily"),
    WEEKLY("weekly"),
    MONTHLY("monthly");

    private final String wire;

    ScheduleFrequency(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    /** @throws IllegalArgumentException for a value that is not {@code daily}, {@code weekly} or {@code monthly} */
    public static ScheduleFrequency fromWire(String wire) {
        return Arrays.stream(values())
                .filter(frequency -> frequency.wire.equals(wire))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown schedule frequency: " + wire));
    }
}
