package com.flowcortex.credit.api;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** The unit a rate-card row prices in, with the units a call consumes and the quantity it must declare. */
public enum PricingUnit {
    PER_REQUEST("per_request"),
    PER_ONBOARDING("per_onboarding"),
    PER_IMAGE("per_image"),
    PER_1K_CHARS("per_1k_chars"),
    PER_SECOND_VIDEO("per_second_video"),
    PER_MINUTE("per_minute"),
    PER_MINUTE_AUDIO("per_minute_audio"),
    PER_MEGAPIXEL("per_megapixel"),
    PER_MEGAPIXEL_SECOND("per_megapixel_second"),
    PER_VIDEO("per_video"),
    PER_1M_TOKENS_IN("per_1m_tokens_in"),
    PER_1M_TOKENS_OUT("per_1m_tokens_out");

    private static final Pattern NUMERIC = Pattern.compile("^\\d+(\\.\\d+)?$");
    private static final BigDecimal DEFAULT_SIDE_PX = BigDecimal.valueOf(1024);
    private static final BigDecimal SECONDS_PER_MINUTE = BigDecimal.valueOf(60);
    private static final BigDecimal THOUSAND = BigDecimal.valueOf(1_000);
    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

    private final String wire;

    PricingUnit(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public static Optional<PricingUnit> fromWire(String wire) {
        for (PricingUnit unit : values()) {
            if (unit.wire.equals(wire)) {
                return Optional.of(unit);
            }
        }
        return Optional.empty();
    }

    /** Units one call consumes in this unit; omitted quantity fields take their defaults. */
    public BigDecimal units(RateQuantity q) {
        return switch (this) {
            case PER_REQUEST -> count(q.requests());
            case PER_IMAGE -> count(q.numImages());
            case PER_ONBOARDING, PER_VIDEO -> BigDecimal.ONE;
            case PER_SECOND_VIDEO -> nonNull(q.sec());
            case PER_MINUTE, PER_MINUTE_AUDIO -> nonNull(q.sec()).divide(SECONDS_PER_MINUTE, MathContext.DECIMAL128);
            case PER_1K_CHARS -> number(q.chars(), BigDecimal.ZERO).divide(THOUSAND, MathContext.DECIMAL128);
            case PER_MEGAPIXEL -> number(q.widthPx(), DEFAULT_SIDE_PX).multiply(number(q.heightPx(), DEFAULT_SIDE_PX))
                    .divide(MILLION, MathContext.DECIMAL128);
            case PER_MEGAPIXEL_SECOND -> nonNull(q.megapixelSeconds());
            case PER_1M_TOKENS_IN -> number(q.tokensIn(), BigDecimal.ZERO).divide(MILLION, MathContext.DECIMAL128);
            case PER_1M_TOKENS_OUT -> number(q.tokensOut(), BigDecimal.ZERO).divide(MILLION, MathContext.DECIMAL128);
        };
    }

    /** The step parameters the quantity predicate reads for this unit. */
    public Set<String> quantityParams() {
        return switch (this) {
            case PER_IMAGE -> Set.of("numImages");
            case PER_1K_CHARS -> Set.of("text");
            case PER_SECOND_VIDEO, PER_MINUTE -> Set.of("durationSec");
            default -> Set.of();
        };
    }

    /**
     * Whether the call's billable quantity is fixed by the resolved parameters. Keyed on the unit, never on the
     * capability; any warning, a null or unknown unit, and every metered unit it does not read answer false.
     */
    public static boolean quantityKnownFor(String pricingUnit, Map<String, ?> resolvedParams, List<String> warnings) {
        if (warnings != null && !warnings.isEmpty()) {
            return false;
        }
        Map<String, ?> params = resolvedParams == null ? Map.of() : resolvedParams;
        Optional<PricingUnit> unit = fromWire(pricingUnit);
        if (unit.isEmpty()) {
            return false;
        }
        return switch (unit.get()) {
            case PER_REQUEST, PER_ONBOARDING -> true;
            case PER_IMAGE -> params.get("numImages") == null || declared(params.get("numImages"));
            case PER_1K_CHARS -> params.get("text") instanceof String text && !text.isBlank() && !text.contains("{");
            case PER_SECOND_VIDEO, PER_MINUTE -> declared(params.get("durationSec"));
            default -> false;
        };
    }

    private static boolean declared(Object value) {
        if (value instanceof Number n) {
            double d = n.doubleValue();
            return Double.isFinite(d) && d > 0;
        }
        if (value instanceof String s && NUMERIC.matcher(s).matches()) {
            return new BigDecimal(s).signum() > 0;
        }
        return false;
    }

    private static BigDecimal count(Long value) {
        return number(value, BigDecimal.ONE);
    }

    private static BigDecimal number(Long value, BigDecimal fallback) {
        return value == null ? fallback : BigDecimal.valueOf(value);
    }

    private static BigDecimal nonNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
