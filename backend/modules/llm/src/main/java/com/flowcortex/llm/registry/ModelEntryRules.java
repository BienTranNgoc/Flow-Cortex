package com.flowcortex.llm.registry;

import com.flowcortex.llm.registry.RegisteredProvider.ProviderCategory;
import com.flowcortex.shared.web.LocalizedMessage;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The rules a model entry must meet beyond the request's own bounds: its provider is registered and serves
 * language models, and that provider accepts the id. Pure: no write happens here.
 */
public final class ModelEntryRules {

    static final int DEFAULT_MAX_OUTPUT_TOKENS = 4096;

    private ModelEntryRules() {
    }

    /** An entry as the console sent it; absent optional fields take their defaults in {@link #withDefaults}. */
    public record ModelDraft(String modelKey, String providerKey, String displayName, Integer maxOutputTokens,
            Boolean supportsVision, Boolean supportsAudio, Boolean active, Short displayOrder) {
    }

    /** An entry with every field decided. */
    public record ModelEntry(String modelKey, String providerKey, String displayName, int maxOutputTokens,
            boolean supportsVision, boolean supportsAudio, boolean active, short displayOrder) {
    }

    /** Field name to message; empty when the draft is acceptable. */
    public static Map<String, LocalizedMessage> validate(ModelDraft draft, ProviderRegistry registry) {
        Map<String, LocalizedMessage> errors = new LinkedHashMap<>();
        Optional<RegisteredProvider> provider = registry.find(draft.providerKey());
        if (provider.isEmpty()) {
            errors.put("providerKey", new LocalizedMessage("llm.field.providerUnregistered"));
        } else if (provider.get().category() != ProviderCategory.TEXT) {
            errors.put("providerKey", new LocalizedMessage("llm.field.providerNotText"));
        } else if (!provider.get().acceptsModelKey(draft.modelKey())) {
            errors.put("modelKey", new LocalizedMessage("llm.field.modelKeyRejected"));
        }
        return errors;
    }

    public static ModelEntry withDefaults(ModelDraft draft) {
        return new ModelEntry(draft.modelKey(), draft.providerKey(), draft.displayName(),
                draft.maxOutputTokens() == null ? DEFAULT_MAX_OUTPUT_TOKENS : draft.maxOutputTokens(),
                Boolean.TRUE.equals(draft.supportsVision()), Boolean.TRUE.equals(draft.supportsAudio()),
                draft.active() == null || draft.active(),
                draft.displayOrder() == null ? 0 : draft.displayOrder());
    }
}
