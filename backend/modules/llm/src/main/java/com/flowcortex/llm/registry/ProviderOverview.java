package com.flowcortex.llm.registry;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The console's view of every registered provider: its settings by name, and each slot's models with
 * what the registry says about them. Built from {@link ProviderSnapshot}s that hold names and flags only,
 * so no setting value can reach the response.
 */
@Schema(name = "AiProviderOverview")
public record ProviderOverview(List<CategoryGroup> categories) {

    public ProviderOverview {
        categories = List.copyOf(categories);
    }

    /** How the registry stands on one slot model. */
    public enum ModelStatus {
        ACTIVE,
        INACTIVE,
        UNREGISTERED,
        NOT_APPLICABLE;

        public String value() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    @Schema(name = "AiProviderCategory")
    public record CategoryGroup(String category, List<ProviderView> providers) {

        public CategoryGroup {
            providers = List.copyOf(providers);
        }
    }

    @Schema(name = "AiProviderView")
    public record ProviderView(String key, boolean configured, List<String> missingSettings, boolean liveCheckable,
            List<SlotModels> slots) {

        public ProviderView {
            missingSettings = List.copyOf(missingSettings);
            slots = List.copyOf(slots);
        }
    }

    @Schema(name = "AiProviderSlot")
    public record SlotModels(String name, List<SlotModel> models) {

        public SlotModels {
            models = List.copyOf(models);
        }
    }

    /** {@code status} is one of {@link ModelStatus#value()}. */
    @Schema(name = "AiProviderSlotModel")
    public record SlotModel(String modelKey, String status) {
    }

    /** One provider reduced to names and flags. */
    public record ProviderSnapshot(String key, String category, boolean configured, List<String> missingSettings,
            boolean liveCheckable, List<SlotSnapshot> slots) {

        public ProviderSnapshot {
            missingSettings = List.copyOf(missingSettings);
            slots = List.copyOf(slots);
        }
    }

    public record SlotSnapshot(String name, List<ModelSnapshot> models) {

        public SlotSnapshot {
            models = List.copyOf(models);
        }
    }

    /** {@code applicable} is false when the provider refuses the id as a registry entry. */
    public record ModelSnapshot(String modelKey, boolean applicable) {
    }

    public static ProviderSnapshot snapshot(RegisteredProvider provider) {
        RegisteredProvider.ConfigStatus status = provider.configStatus();
        List<SlotSnapshot> slots = provider.slots().stream()
                .map(slot -> new SlotSnapshot(slot.name(), slot.models().stream()
                        .map(model -> new ModelSnapshot(model, provider.acceptsModelKey(model)))
                        .toList()))
                .toList();
        return new ProviderSnapshot(provider.key(), provider.category().name().toLowerCase(Locale.ROOT),
                status.configured(), status.missingSettings(), provider.liveCheckable(), slots);
    }

    /** The slot model ids the registry has to be asked about: each once, without those no entry can name. */
    public static Set<String> slotModelKeys(List<RegisteredProvider> providers) {
        Set<String> keys = new LinkedHashSet<>();
        for (RegisteredProvider provider : providers) {
            for (RegisteredProvider.SlotView slot : provider.slots()) {
                for (String model : slot.models()) {
                    if (provider.acceptsModelKey(model)) {
                        keys.add(model);
                    }
                }
            }
        }
        return keys;
    }

    /**
     * @param activeByModelKey the {@code active} flag of every registry entry among the slot models; a model
     *        with no key here has no entry
     */
    public static ProviderOverview assemble(List<ProviderSnapshot> providers, Map<String, Boolean> activeByModelKey) {
        Map<String, List<ProviderView>> byCategory = new LinkedHashMap<>();
        for (ProviderSnapshot provider : providers) {
            List<SlotModels> slots = new ArrayList<>();
            for (SlotSnapshot slot : provider.slots()) {
                slots.add(new SlotModels(slot.name(), slot.models().stream()
                        .map(model -> new SlotModel(model.modelKey(), status(model, activeByModelKey).value()))
                        .toList()));
            }
            byCategory.computeIfAbsent(provider.category(), category -> new ArrayList<>())
                    .add(new ProviderView(provider.key(), provider.configured(), provider.missingSettings(),
                            provider.liveCheckable(), slots));
        }
        return new ProviderOverview(byCategory.entrySet().stream()
                .map(group -> new CategoryGroup(group.getKey(), group.getValue()))
                .toList());
    }

    private static ModelStatus status(ModelSnapshot model, Map<String, Boolean> activeByModelKey) {
        if (!model.applicable()) {
            return ModelStatus.NOT_APPLICABLE;
        }
        Boolean active = activeByModelKey.get(model.modelKey());
        if (active == null) {
            return ModelStatus.UNREGISTERED;
        }
        return active ? ModelStatus.ACTIVE : ModelStatus.INACTIVE;
    }
}
