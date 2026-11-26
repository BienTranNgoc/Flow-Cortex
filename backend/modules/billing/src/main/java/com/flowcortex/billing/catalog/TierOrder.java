package com.flowcortex.billing.catalog;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.TreeMap;

/**
 * The tier-order rule over the tiers on sale: positions are unique, every tier has a monthly price, and a higher
 * position costs strictly more per month. {@link #violations} names every break at once, in a stable order.
 */
public final class TierOrder {

    private static final Comparator<OrderableTier> BY_POSITION_THEN_ID =
            Comparator.comparingInt(OrderableTier::sortOrder).thenComparing(OrderableTier::id);

    private TierOrder() {
    }

    /** A tier on sale with the price of its unretired monthly version, if it has one. */
    public record OrderableTier(String id, int sortOrder, OptionalLong monthlyPriceVnd) {
    }

    /** One way the ladder breaks the rule. */
    public sealed interface TierOrderViolation {

        /** The fixed word that tags the metric and the log line. */
        String kind();

        /** A position held by more than one tier; {@code tierIds} is sorted. */
        record DuplicatePosition(int sortOrder, List<String> tierIds) implements TierOrderViolation {

            @Override
            public String kind() {
                return "duplicate_position";
            }
        }

        /** A tier on sale with no unretired monthly version. */
        record Unpriced(String tierId, int sortOrder) implements TierOrderViolation {

            @Override
            public String kind() {
                return "unpriced";
            }
        }

        /** Neighbouring priced tiers where the higher position costs the same or less per month. */
        record PriceInversion(String lowerTierId, int lowerSortOrder, long lowerPriceVnd, String higherTierId,
                int higherSortOrder, long higherPriceVnd) implements TierOrderViolation {

            @Override
            public String kind() {
                return "price_inversion";
            }
        }
    }

    /**
     * Duplicate positions by ascending position, then unpriced tiers by id, then inversions by position. Priced
     * neighbours are compared by position then id; a pair at one position is the duplicate's business, and an
     * unpriced tier is left out of the comparison.
     */
    public static List<TierOrderViolation> violations(List<OrderableTier> tiers) {
        List<TierOrderViolation> found = new ArrayList<>();

        Map<Integer, List<String>> byPosition = new TreeMap<>();
        for (OrderableTier tier : tiers) {
            byPosition.computeIfAbsent(tier.sortOrder(), position -> new ArrayList<>()).add(tier.id());
        }
        byPosition.forEach((position, ids) -> {
            if (ids.size() > 1) {
                found.add(new TierOrderViolation.DuplicatePosition(position, ids.stream().sorted().toList()));
            }
        });

        tiers.stream()
                .filter(tier -> tier.monthlyPriceVnd().isEmpty())
                .sorted(Comparator.comparing(OrderableTier::id))
                .forEach(tier -> found.add(new TierOrderViolation.Unpriced(tier.id(), tier.sortOrder())));

        List<OrderableTier> priced = tiers.stream()
                .filter(tier -> tier.monthlyPriceVnd().isPresent())
                .sorted(BY_POSITION_THEN_ID)
                .toList();
        for (int i = 1; i < priced.size(); i++) {
            OrderableTier lower = priced.get(i - 1);
            OrderableTier higher = priced.get(i);
            if (lower.sortOrder() == higher.sortOrder()) {
                continue;
            }
            long lowerPrice = lower.monthlyPriceVnd().getAsLong();
            long higherPrice = higher.monthlyPriceVnd().getAsLong();
            if (higherPrice <= lowerPrice) {
                found.add(new TierOrderViolation.PriceInversion(lower.id(), lower.sortOrder(), lowerPrice,
                        higher.id(), higher.sortOrder(), higherPrice));
            }
        }
        return List.copyOf(found);
    }

    /** One log sentence naming the kind, the tiers, their positions and their prices. */
    public static String describe(TierOrderViolation violation) {
        return switch (violation) {
            case TierOrderViolation.DuplicatePosition duplicate -> "duplicate_position position=%d tiers=%s"
                    .formatted(duplicate.sortOrder(), String.join(",", duplicate.tierIds()));
            case TierOrderViolation.Unpriced unpriced -> "unpriced tier=%s position=%d"
                    .formatted(unpriced.tierId(), unpriced.sortOrder());
            case TierOrderViolation.PriceInversion inversion ->
                    "price_inversion lower=%s lower_position=%d lower_price_vnd=%d higher=%s higher_position=%d higher_price_vnd=%d"
                            .formatted(inversion.lowerTierId(), inversion.lowerSortOrder(), inversion.lowerPriceVnd(),
                                    inversion.higherTierId(), inversion.higherSortOrder(), inversion.higherPriceVnd());
        };
    }
}
