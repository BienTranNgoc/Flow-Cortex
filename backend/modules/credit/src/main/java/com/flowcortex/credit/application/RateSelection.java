package com.flowcortex.credit.application;

import com.flowcortex.credit.domain.RateCard;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Picks the row that prices a call at an instant. */
final class RateSelection {

    private RateSelection() {
    }

    /**
     * The one row of the key active at {@code at}. When any row of the key carries dimensions, only a row whose
     * dimensions equal the call's prices it, and a call without options has none. Two rows active at one instant
     * is a data defect and throws.
     */
    static Optional<RateCard> active(List<RateCard> rowsOfKey, Instant at, Map<String, Object> callDimensions) {
        boolean dimensioned = rowsOfKey.stream().anyMatch(RateCard::hasDimensions);
        boolean callHasOptions = callDimensions != null && !callDimensions.isEmpty();
        if (dimensioned && !callHasOptions) {
            return Optional.empty();
        }
        List<RateCard> active = rowsOfKey.stream()
                .filter(row -> !dimensioned || RateCard.dimensionsEqual(row.dimensions(), callDimensions))
                .filter(row -> row.activeAt(at))
                .toList();
        if (active.size() > 1) {
            RateCard first = active.get(0);
            throw new IllegalStateException("Rate card " + first.ref() + " has " + active.size()
                    + " rows active at " + at + " for one key (" + first.unit().wire() + ", " + first.provider() + ")");
        }
        return active.stream().findFirst();
    }
}
