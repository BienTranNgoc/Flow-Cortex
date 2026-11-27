package com.flowcortex.llm.catalog;

import com.flowcortex.llm.api.ModelDescriptor;
import com.flowcortex.llm.api.ModelDescriptor.Status;
import com.flowcortex.llm.api.ModelDescriptors;
import com.flowcortex.llm.registry.RegisteredProvider;
import com.flowcortex.llm.registry.RegisteredProvider.ProviderCategory;
import com.flowcortex.shared.web.LocalizedMessage;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * The rules a catalog model row must meet beyond the request's own bounds. Only a row of mode {@code text}
 * is checked: its provider is a registered text provider, then its model key has a descriptor of that
 * provider, then that descriptor is not deprecated. Pure: no write happens here.
 */
public final class ModelRowRules {

    static final String TEXT_MODE = "text";

    private ModelRowRules() {
    }

    /** Whether the row is new or replaces a stored one. */
    public enum Operation {
        CREATE,
        REPLACE
    }

    /** A row as the console sent it, every optional flag already decided. */
    public record ModelRowDraft(String modelKey, String providerKey, String vendorLabel, String makerLabel,
            String mode, boolean enabled, boolean notImplemented) {
    }

    /**
     * Field name to message; empty when the draft is acceptable. A replace that leaves the row disabled
     * passes, so a model a release deprecated or dropped can always be switched off.
     */
    public static Map<String, LocalizedMessage> validate(ModelRowDraft draft, Operation operation,
            Function<String, Optional<RegisteredProvider>> providers, ModelDescriptors descriptors) {
        Map<String, LocalizedMessage> errors = new LinkedHashMap<>();
        if (!TEXT_MODE.equals(draft.mode()) || (operation == Operation.REPLACE && !draft.enabled())) {
            return errors;
        }
        Optional<RegisteredProvider> provider = providers.apply(draft.providerKey());
        if (provider.isEmpty()) {
            errors.put("providerKey", new LocalizedMessage("llm.field.providerUnregistered"));
            return errors;
        }
        if (provider.get().category() != ProviderCategory.TEXT) {
            errors.put("providerKey", new LocalizedMessage("llm.field.providerNotText"));
            return errors;
        }
        Optional<ModelDescriptor> descriptor = descriptors.describe(draft.modelKey())
                .filter(found -> found.providerKey().equals(draft.providerKey()));
        if (descriptor.isEmpty()) {
            errors.put("modelKey", new LocalizedMessage("llm.field.modelNotDescribed"));
        } else if (descriptor.get().status() == Status.DEPRECATED) {
            errors.put("modelKey", new LocalizedMessage("llm.field.modelDeprecated"));
        }
        return errors;
    }
}
