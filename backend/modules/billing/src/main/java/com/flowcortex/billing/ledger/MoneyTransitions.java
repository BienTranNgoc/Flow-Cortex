package com.flowcortex.billing.ledger;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * Which moves between money statuses are permitted. The rank check runs before the table check, so a table
 * entry that goes backwards can never be applied.
 */
public final class MoneyTransitions {

    private static final Map<MoneyStatus, Set<MoneyStatus>> PERMITTED = permitted();

    private MoneyTransitions() {
    }

    /** What to do with a proposed move. */
    public sealed interface Verdict permits Apply, Noop, Refused {
    }

    /** The move is permitted; {@code from} is the status it leaves. */
    public record Apply(MoneyStatus from) implements Verdict {
    }

    /** The payment already holds the status. */
    public record Noop() implements Verdict {
    }

    /** The move is not allowed: {@code backward_transition} or {@code undeclared_transition}. */
    public record Refused(String reason) implements Verdict {
    }

    public static Verdict evaluate(MoneyStatus from, MoneyStatus to) {
        if (from == to) {
            return new Noop();
        }
        if (to.rank() < from.rank()) {
            return new Refused("backward_transition");
        }
        if (!PERMITTED.getOrDefault(from, Set.of()).contains(to)) {
            return new Refused("undeclared_transition");
        }
        return new Apply(from);
    }

    static Map<MoneyStatus, Set<MoneyStatus>> permittedMoves() {
        return PERMITTED;
    }

    private static Map<MoneyStatus, Set<MoneyStatus>> permitted() {
        Map<MoneyStatus, Set<MoneyStatus>> table = new EnumMap<>(MoneyStatus.class);
        table.put(MoneyStatus.PENDING, Set.of(MoneyStatus.SUCCEEDED, MoneyStatus.FAILED, MoneyStatus.EXPIRED));
        table.put(MoneyStatus.SUCCEEDED,
                Set.of(MoneyStatus.PARTIALLY_REFUNDED, MoneyStatus.REFUNDED, MoneyStatus.DISPUTED));
        table.put(MoneyStatus.PARTIALLY_REFUNDED, Set.of(MoneyStatus.REFUNDED, MoneyStatus.DISPUTED));
        table.put(MoneyStatus.REFUNDED, Set.of(MoneyStatus.DISPUTED));
        table.put(MoneyStatus.EXPIRED, Set.of(MoneyStatus.SUCCEEDED));
        return Map.copyOf(table);
    }
}
