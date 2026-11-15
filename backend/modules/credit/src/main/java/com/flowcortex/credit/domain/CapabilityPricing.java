package com.flowcortex.credit.domain;

import com.flowcortex.credit.api.PricingUnit;
import java.util.List;
import java.util.Optional;

/**
 * How one step type or media capability is priced: through a rate-card key, free, or sampled ({@code mc}, not yet
 * priceable). The ref, unit and provider are null unless the kind is {@link Kind#RATE_CARD}.
 */
public record CapabilityPricing(
        String capability,
        Kind kind,
        String ref,
        PricingUnit unit,
        String provider,
        List<String> quantityParams) {

    public CapabilityPricing {
        quantityParams = quantityParams == null ? List.of() : List.copyOf(quantityParams);
    }

    public enum Kind {
        RATE_CARD("rate_card"),
        FREE("free"),
        MC("mc");

        private final String wire;

        Kind(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }

        public static Optional<Kind> fromWire(String wire) {
            for (Kind kind : values()) {
                if (kind.wire.equals(wire)) {
                    return Optional.of(kind);
                }
            }
            return Optional.empty();
        }
    }
}
