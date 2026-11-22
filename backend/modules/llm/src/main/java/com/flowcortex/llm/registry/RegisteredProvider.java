package com.flowcortex.llm.registry;

import java.util.List;

/**
 * One hosted provider the backend can call. The registry lists every bean of this type; a later provider
 * adds a bean inside this module and nothing else.
 */
public interface RegisteredProvider {

    /** Stable provider key, for example {@code gemini}. */
    String key();

    ProviderCategory category();

    /** Whether the provider's settings are complete, and the NAMES of those that are not. */
    ConfigStatus configStatus();

    /** True when {@link #liveCheck()} exists and spends no generation quota. */
    boolean liveCheckable();

    /** Called only when the provider is configured and {@link #liveCheckable()}. */
    ProbeResult liveCheck();

    /** Whether the id is a model of this provider that a registry entry may name. */
    boolean acceptsModelKey(String modelKey);

    /** The provider's own list of models; used for text providers. */
    VendorModels listModels();

    /** Sends one small call to the model and reports the outcome; the caller has checked configuration. */
    ProbeResult testModel(String modelKey);

    /** The configured slots, each with its models in order. */
    List<SlotView> slots();

    enum ProviderCategory {
        TEXT
    }

    record ConfigStatus(boolean configured, List<String> missingSettings) {

        public ConfigStatus {
            missingSettings = List.copyOf(missingSettings);
        }
    }

    record VendorModel(String id, String displayName) {
    }

    /** {@code failure} is a probe code when the list could not be read, else {@code null}. */
    record VendorModels(List<VendorModel> models, String failure) {

        public VendorModels {
            models = List.copyOf(models);
        }
    }

    record SlotView(String name, List<String> models) {

        public SlotView {
            models = List.copyOf(models);
        }
    }
}
