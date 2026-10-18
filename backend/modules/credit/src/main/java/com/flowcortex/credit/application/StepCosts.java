package com.flowcortex.credit.application;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** The credit cost of each step type, from {@code flowcortex.credits.step-costs.<type>}. */
final class StepCosts {

    private final Map<String, Long> byType;

    StepCosts(Map<String, Long> byType) {
        Map<String, Long> costs = byType == null ? Map.of() : byType;
        costs.forEach((type, cost) -> {
            if (cost == null || cost < 0) {
                throw new IllegalArgumentException(
                        "flowcortex.credits.step-costs." + type + " must be zero or more, was " + cost);
            }
        });
        this.byType = Map.copyOf(costs);
    }

    long costOf(String type) {
        Long cost = byType.get(type);
        if (cost == null) {
            throw new IllegalStateException("No credit cost configured for step type " + type);
        }
        return cost;
    }

    long estimate(List<String> types) {
        return types.stream().mapToLong(this::costOf).sum();
    }

    void requirePriced(Collection<String> types) {
        TreeSet<String> missing = new TreeSet<>(types);
        missing.removeAll(byType.keySet());
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Every step type needs flowcortex.credits.step-costs.<type>; missing: "
                    + String.join(", ", missing));
        }
    }
}
