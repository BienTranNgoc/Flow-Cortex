package com.flowcortex.llm.gemini;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The named model chains under {@code flowcortex.llm.gemini.slots}. Every check runs here and names
 * the slot, so a mistake stops startup with a message that says where it is.
 */
@Validated
@ConfigurationProperties("flowcortex.llm.gemini")
public record GeminiSlotProperties(Map<String, Slot> slots, String safetyThreshold) {

    private static final String SAFETY_KEY = "flowcortex.llm.gemini.safety-threshold";
    private static final String DEFAULT_SAFETY_THRESHOLD = "BLOCK_MEDIUM_AND_ABOVE";
    private static final Set<String> THINKING_VALUES = Set.of("off", "default");

    public GeminiSlotProperties {
        slots = slots == null ? Map.of() : Map.copyOf(slots);
        safetyThreshold = safetyThreshold == null ? DEFAULT_SAFETY_THRESHOLD : safetyThreshold;
        GeminiSafety.check(SAFETY_KEY, safetyThreshold);
        slots.forEach(GeminiSlotProperties::check);
    }

    static GeminiSlotProperties of(Map<String, Slot> slots) {
        return new GeminiSlotProperties(slots, null);
    }

    /** @throws IllegalArgumentException when no such slot is configured */
    Slot slot(String name) {
        Slot slot = slots.get(name);
        if (slot == null) {
            throw new IllegalArgumentException("No Gemini slot named " + name + " in flowcortex.llm.gemini.slots");
        }
        return slot;
    }

    private static void check(String name, Slot slot) {
        String prefix = "flowcortex.llm.gemini.slots." + name;
        if (slot.models().isEmpty()) {
            throw new IllegalArgumentException(prefix + ".models needs at least one model");
        }
        if (slot.models().stream().anyMatch(model -> model == null || model.isBlank())) {
            throw new IllegalArgumentException(prefix + ".models has a blank model");
        }
        if (slot.attempts() < 1) {
            throw new IllegalArgumentException(prefix + ".attempts must be at least 1, got " + slot.attempts());
        }
        if (slot.retryWaits().size() != slot.attempts() - 1) {
            throw new IllegalArgumentException(prefix + ".retry-waits needs " + (slot.attempts() - 1)
                    + " waits between " + slot.attempts() + " attempts, got " + slot.retryWaits().size());
        }
        if (slot.retryWaits().stream().anyMatch(wait -> wait == null || wait.isNegative())) {
            throw new IllegalArgumentException(prefix + ".retry-waits must not be negative");
        }
        if (slot.callTimeout() == null || slot.callTimeout().isZero() || slot.callTimeout().isNegative()) {
            throw new IllegalArgumentException(prefix + ".call-timeout must be a positive duration");
        }
        if (slot.thinking() != null && !THINKING_VALUES.contains(slot.thinking().toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException(prefix + ".thinking must be 'off' or 'default', got '"
                    + slot.thinking() + "'");
        }
        if (slot.safetyThreshold() != null) {
            GeminiSafety.check(prefix + ".safety-threshold", slot.safetyThreshold());
        }
    }

    /**
     * One chain: each model gets {@code attempts} tries, separated by {@code retryWaits} in order, each
     * try bounded by {@code callTimeout}. {@code thinking} is {@code off} or {@code default} (unset means
     * default); {@code safetyThreshold} overrides the global one when set.
     */
    public record Slot(List<String> models, int attempts, List<Duration> retryWaits, Duration callTimeout,
            String thinking, String safetyThreshold) {

        public Slot {
            models = models == null ? List.of() : List.copyOf(models);
            retryWaits = retryWaits == null ? List.of() : List.copyOf(retryWaits);
        }

        static Slot of(List<String> models, int attempts, List<Duration> retryWaits, Duration callTimeout) {
            return new Slot(models, attempts, retryWaits, callTimeout, null, null);
        }

        GeminiThinkingFloor.Mode thinkingMode() {
            return "off".equalsIgnoreCase(thinking) ? GeminiThinkingFloor.Mode.OFF : GeminiThinkingFloor.Mode.DEFAULT;
        }

        String thresholdOr(String global) {
            return safetyThreshold != null ? safetyThreshold : global;
        }

        /** Every attempt timing out and every wait taken, on every model. */
        Duration jsonWorstCase() {
            return perModelWorstCase().multipliedBy(models.size());
        }

        /** Every attempt on the first model, and one call timing out on each later one. */
        Duration speechWorstCase() {
            return perModelWorstCase().plus(callTimeout.multipliedBy(models.size() - 1L));
        }

        private Duration perModelWorstCase() {
            Duration total = callTimeout.multipliedBy(attempts);
            for (Duration wait : retryWaits) {
                total = total.plus(wait);
            }
            return total;
        }
    }
}
