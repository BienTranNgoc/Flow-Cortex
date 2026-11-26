package com.flowcortex.billing.ledger;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The item reference of a plan purchase: {@code <tier>:<cycle>}, for example {@code basic:monthly}. The tier
 * follows the catalogue's id shape and the cycle is one of the two the catalogue prices.
 */
public record PlanItemRef(String tierId, String cycle) {

    private static final Pattern PLAN_REF = Pattern.compile("([a-z][a-z0-9_]{0,19}):(monthly|yearly)");

    /** The tier and cycle a reference names, or empty when it names no plan. */
    public static Optional<PlanItemRef> parse(String itemRef) {
        if (itemRef == null) {
            return Optional.empty();
        }
        Matcher matcher = PLAN_REF.matcher(itemRef);
        return matcher.matches() ? Optional.of(new PlanItemRef(matcher.group(1), matcher.group(2))) : Optional.empty();
    }
}
