package com.flowcortex.workflow.application;

/** Pure discovery scores computed from the windowed figures. */
public final class DiscoveryScores {

    private static final long RUNNER_WEIGHT = 3;
    private static final long SAVE_WEIGHT = 5;

    private DiscoveryScores() {
    }

    /** Three points per distinct runner and five per save of the last seven days, capped at {@link Integer#MAX_VALUE}. */
    public static int trending(int runners7d, int saves7d) {
        long score = RUNNER_WEIGHT * runners7d + SAVE_WEIGHT * saves7d;
        return (int) Math.min(score, Integer.MAX_VALUE);
    }
}
