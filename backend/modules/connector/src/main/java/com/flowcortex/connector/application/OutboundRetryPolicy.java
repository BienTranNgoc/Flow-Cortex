package com.flowcortex.connector.application;

/** Retries only a request that was never written, so a slow send cannot be posted twice. */
public final class OutboundRetryPolicy {

    public static final int MAX_ATTEMPTS = 2;

    private OutboundRetryPolicy() {
    }

    /**
     * @param attempt the 1-based number of the attempt that just failed
     * @return whether another attempt may follow, for every HTTP method alike
     */
    public static boolean retry(OutboundFailurePhase phase, int attempt) {
        return phase == OutboundFailurePhase.CONNECT_PHASE && attempt < MAX_ATTEMPTS;
    }
}
