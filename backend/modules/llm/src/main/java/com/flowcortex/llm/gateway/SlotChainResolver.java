package com.flowcortex.llm.gateway;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Which of a slot's configured models a call walks, given the registry's active flags. A model with no
 * registry entry stays. The chain never comes out empty: a registry that cannot be read, or that
 * switches every model off, degrades to configured models instead of failing the call.
 */
public final class SlotChainResolver {

    /** Registry could not be read; the configured chain is served as is. */
    public static final String REGISTRY_UNREADABLE = "registry_unreadable";
    /** Every configured model is inactive; the first configured model is served alone. */
    public static final String ALL_INACTIVE = "all_inactive";

    /**
     * @param models the chain to walk, in order
     * @param skipped the configured models left out because the registry marks them inactive
     * @param degradedReason {@link #REGISTRY_UNREADABLE}, {@link #ALL_INACTIVE}, or {@code null} when the
     *     chain resolved normally
     */
    public record Resolution(List<String> models, List<String> skipped, String degradedReason) {
    }

    private SlotChainResolver() {
    }

    /**
     * @param configured the slot's models in configured order
     * @param activity active flag by model id, read once per call; absent ids have no entry
     */
    public static Resolution resolve(List<String> configured, Supplier<Map<String, Boolean>> activity) {
        Map<String, Boolean> flags;
        try {
            flags = activity.get();
        } catch (RuntimeException ex) {
            return new Resolution(List.copyOf(configured), List.of(), REGISTRY_UNREADABLE);
        }
        if (flags == null) {
            flags = Map.of();
        }
        Map<String, Boolean> byModel = flags;
        List<String> active = configured.stream().filter(model -> !Boolean.FALSE.equals(byModel.get(model))).toList();
        if (active.isEmpty() && !configured.isEmpty()) {
            return new Resolution(List.of(configured.get(0)), List.of(), ALL_INACTIVE);
        }
        List<String> skipped = configured.stream().filter(model -> Boolean.FALSE.equals(byModel.get(model))).toList();
        return new Resolution(active, skipped, null);
    }
}
