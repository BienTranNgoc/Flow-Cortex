package com.flowcortex.llm.registry;

import java.util.List;

/**
 * What a model test or a provider test came to. {@code tested} is false when nothing was sent (no free
 * check, or the provider lacks settings); {@code code} names the failure and is absent on success;
 * {@code missingSettings} holds setting names only, never values.
 */
public record ProbeResult(boolean ok, boolean tested, String code, long latencyMs, Integer outputTokens,
        List<String> missingSettings) {

    public ProbeResult {
        missingSettings = List.copyOf(missingSettings);
    }

    public static ProbeResult answered(long latencyMs, int outputTokens) {
        return new ProbeResult(true, true, null, latencyMs, outputTokens, List.of());
    }

    /** A check that reached the provider and was accepted, with no generation behind it. */
    public static ProbeResult reachable(long latencyMs) {
        return new ProbeResult(true, true, null, latencyMs, null, List.of());
    }

    /** The provider is configured but offers no check that spends no quota, so nothing was sent. */
    public static ProbeResult notTested() {
        return new ProbeResult(true, false, null, 0, null, List.of());
    }

    public static ProbeResult failed(String code, long latencyMs) {
        return new ProbeResult(false, true, code, latencyMs, null, List.of());
    }

    /** Nothing was sent: the provider lacks settings. */
    public static ProbeResult missingConfig(List<String> missingSettings) {
        return new ProbeResult(false, false, ProbeCodes.MISSING_CONFIG, 0, null, missingSettings);
    }
}
