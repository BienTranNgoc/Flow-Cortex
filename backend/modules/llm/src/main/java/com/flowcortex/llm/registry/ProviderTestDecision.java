package com.flowcortex.llm.registry;

import java.util.List;
import java.util.Optional;

/**
 * What a provider test does, decided by one ordered rule table: no such provider, then missing settings,
 * then a provider with no free check, then the live check. The first rule that holds wins, so a provider
 * that is both unconfigured and live-checkable reports its missing settings and sends nothing.
 */
public record ProviderTestDecision(Kind kind, List<String> missingSettings) {

    public enum Kind {
        NOT_FOUND,
        MISSING_CONFIG,
        CONFIG_ONLY,
        LIVE_CHECK
    }

    public ProviderTestDecision {
        missingSettings = List.copyOf(missingSettings);
    }

    public static ProviderTestDecision decide(Optional<RegisteredProvider> provider) {
        if (provider.isEmpty()) {
            return new ProviderTestDecision(Kind.NOT_FOUND, List.of());
        }
        RegisteredProvider found = provider.get();
        RegisteredProvider.ConfigStatus status = found.configStatus();
        if (!status.configured()) {
            return new ProviderTestDecision(Kind.MISSING_CONFIG, status.missingSettings());
        }
        return new ProviderTestDecision(found.liveCheckable() ? Kind.LIVE_CHECK : Kind.CONFIG_ONLY, List.of());
    }
}
