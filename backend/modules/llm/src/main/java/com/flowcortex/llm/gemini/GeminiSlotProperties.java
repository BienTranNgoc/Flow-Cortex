package com.flowcortex.llm.gemini;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The named model chains under {@code flowcortex.llm.gemini.slots}. Every check runs here and names
 * the slot, so a mistake stops startup with a message that says where it is.
 */
@Validated
@ConfigurationProperties("flowcortex.llm.gemini")
public record GeminiSlotProperties(Map<String, Slot> slots) {

    public GeminiSlotProperties {
        slots = slots == null ? Map.of() : Map.copyOf(slots);
        slots.forEach(GeminiSlotProperties::check);
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
    }

    /**
     * One chain: each model gets {@code attempts} tries, separated by {@code retryWaits} in order, each
     * try bounded by {@code callTimeout}.
     */
    public record Slot(List<String> models, int attempts, List<Duration> retryWaits, Duration callTimeout) {

        public Slot {
            models = models == null ? List.of() : List.copyOf(models);
            retryWaits = retryWaits == null ? List.of() : List.copyOf(retryWaits);
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
