package com.flowcortex.credit.application;

import com.flowcortex.credit.api.PricingUnit;
import com.flowcortex.credit.domain.CapabilityPricing;
import com.flowcortex.credit.domain.CapabilityPricing.Kind;
import com.flowcortex.credit.domain.RateCard;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The startup check as one ordered table of pure checks: the first that fails decides a capability's single
 * reason, so a capability never gets two.
 */
final class PricingCoverage {

    /** Why a capability cannot be priced, in the order the checks run. */
    enum Reason {
        NO_MAPPING("no mapping"),
        MC_KIND("kind mc"),
        INVALID_QUANTITY_PARAMS("quantity parameter its unit does not read"),
        OVERLAPPING_ROWS("overlapping rows"),
        NO_ACTIVE_CARD("no active card"),
        COVERAGE_ENDS("coverage ends"),
        QUANTITY_NOT_FIXED("quantity not known without parameters");

        private final String text;

        Reason(String text) {
            this.text = text;
        }

        String text() {
            return text;
        }
    }

    /** A failed check; {@code detail} names the parameter, key or instant, empty when the reason says it all. */
    record Verdict(Reason reason, String detail) {

        String describe() {
            return detail.isEmpty() ? reason.text() : reason.text() + ": " + detail;
        }
    }

    private record Context(String capability, CapabilityPricing mapping, List<RateCard> cards, Instant now) {
    }

    private record Check(Reason reason, Function<Context, Optional<String>> failure) {
    }

    private static final List<Check> CHECKS = List.of(
            new Check(Reason.NO_MAPPING, c -> c.mapping == null ? Optional.of("") : Optional.empty()),
            new Check(Reason.MC_KIND, c -> c.mapping.kind() == Kind.MC ? Optional.of("") : Optional.empty()),
            new Check(Reason.INVALID_QUANTITY_PARAMS, PricingCoverage::unreadParam),
            new Check(Reason.OVERLAPPING_ROWS, c -> onKey(c, PricingCoverage::overlap)),
            new Check(Reason.NO_ACTIVE_CARD, c -> onKey(c, PricingCoverage::noActive)),
            new Check(Reason.COVERAGE_ENDS, c -> onKey(c, PricingCoverage::coverageEnd)),
            new Check(Reason.QUANTITY_NOT_FIXED, PricingCoverage::quantityNotFixed));

    private PricingCoverage() {
    }

    /** Verdicts of the capabilities that cannot be priced at {@code now}, in name order; priced ones are absent. */
    static SortedMap<String, Verdict> verdicts(Collection<String> capabilities,
            Map<String, CapabilityPricing> mappings, Map<String, List<RateCard>> cardsByRef, Instant now) {
        SortedMap<String, Verdict> result = new TreeMap<>();
        for (String capability : capabilities) {
            CapabilityPricing mapping = mappings.get(capability);
            List<RateCard> cards = mapping == null || mapping.ref() == null
                    ? List.of()
                    : cardsByRef.getOrDefault(mapping.ref(), List.of());
            Context context = new Context(capability, mapping, cards, now);
            for (Check check : CHECKS) {
                if (stopsAfterParams(mapping, check)) {
                    break;
                }
                Optional<String> detail = check.failure.apply(context);
                if (detail.isPresent()) {
                    result.put(capability, new Verdict(check.reason, detail.get()));
                    break;
                }
            }
        }
        return result;
    }

    private static boolean stopsAfterParams(CapabilityPricing mapping, Check check) {
        return mapping != null && mapping.kind() == Kind.FREE
                && check.reason.compareTo(Reason.INVALID_QUANTITY_PARAMS) > 0;
    }

    /** The single message startup fails with, naming every unpriced capability and its reason. */
    static String message(SortedMap<String, Verdict> verdicts) {
        return "Every runnable step type needs a price in capability_pricing and rate_card; unpriced: "
                + verdicts.entrySet().stream()
                        .map(e -> e.getKey() + " (" + e.getValue().describe() + ")")
                        .collect(Collectors.joining(", "));
    }

    private static Optional<String> unreadParam(Context c) {
        List<String> params = c.mapping.quantityParams();
        PricingUnit unit = c.mapping.unit();
        return params.stream()
                .filter(p -> c.mapping.kind() != Kind.RATE_CARD || unit == null || !unit.quantityParams().contains(p))
                .findFirst()
                .map(p -> unit == null ? p : p + " is not read by " + unit.wire());
    }

    private static Optional<String> quantityNotFixed(Context c) {
        PricingUnit unit = c.mapping.unit();
        if (unit != null && !PricingUnit.quantityKnownFor(unit.wire(), Map.of(), List.of())) {
            return Optional.of(unit.wire());
        }
        return Optional.empty();
    }

    /** Rows of the mapped key as the step-type paths price it: no model, no dimensions. */
    private static Optional<String> onKey(Context c,
            BiFunction<Context, List<RateCard>, Optional<String>> check) {
        List<RateCard> key = c.cards.stream()
                .filter(r -> r.unit() == c.mapping.unit()
                        && r.provider().equals(c.mapping.provider())
                        && r.model() == null
                        && !r.hasDimensions())
                .sorted(Comparator.comparing(RateCard::effectiveFrom))
                .toList();
        return check.apply(c, key);
    }

    private static Optional<String> overlap(Context c, List<RateCard> key) {
        for (int i = 0; i < key.size(); i++) {
            for (int j = i + 1; j < key.size(); j++) {
                RateCard a = key.get(i);
                RateCard b = key.get(j);
                boolean aBeforeBEnds = b.effectiveTo() == null || a.effectiveFrom().isBefore(b.effectiveTo());
                boolean bBeforeAEnds = a.effectiveTo() == null || b.effectiveFrom().isBefore(a.effectiveTo());
                if (aBeforeBEnds && bBeforeAEnds) {
                    return Optional.of(c.mapping.ref());
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<String> noActive(Context c, List<RateCard> key) {
        return key.stream().anyMatch(r -> r.activeAt(c.now)) ? Optional.empty() : Optional.of(c.mapping.ref());
    }

    private static Optional<String> coverageEnd(Context c, List<RateCard> key) {
        RateCard current = key.stream().filter(r -> r.activeAt(c.now)).findFirst().orElseThrow();
        for (int hops = 0; hops <= key.size(); hops++) {
            Instant end = current.effectiveTo();
            if (end == null) {
                return Optional.empty();
            }
            Optional<RateCard> next = key.stream().filter(r -> r.effectiveFrom().equals(end)).findFirst();
            if (next.isEmpty()) {
                boolean laterRow = key.stream().anyMatch(r -> r.effectiveFrom().isAfter(end));
                return Optional.of(c.mapping.ref() + (laterRow ? ": gap after " : ": ends at ") + end);
            }
            current = next.get();
        }
        return Optional.of(c.mapping.ref() + ": rows chain in a cycle");
    }
}
