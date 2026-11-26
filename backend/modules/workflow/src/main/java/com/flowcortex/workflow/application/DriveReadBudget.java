package com.flowcortex.workflow.application;

import java.time.Duration;

/**
 * What one {@code drive.read} run may spend: no new file starts after {@link #TIME_BUDGET}, which leaves a minute
 * of the five-minute run deadline for the file in flight, and no text is added once the output holds
 * {@link #TEXT_BUDGET} characters, because every later step resolves that output again.
 */
final class DriveReadBudget {

    static final Duration TIME_BUDGET = Duration.ofSeconds(240);
    static final int TEXT_BUDGET = 500_000;

    private DriveReadBudget() {
    }

    static boolean allowsNextFile(Duration elapsed) {
        return elapsed.compareTo(TIME_BUDGET) < 0;
    }

    static boolean allowsText(int totalSoFar) {
        return totalSoFar < TEXT_BUDGET;
    }
}
