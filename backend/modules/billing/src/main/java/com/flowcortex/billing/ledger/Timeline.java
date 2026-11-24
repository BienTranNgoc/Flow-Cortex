package com.flowcortex.billing.ledger;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Reads a payment's status history as the events a member can follow. */
public final class Timeline {

    private static final String PROVIDER_CODE_PREFIX = "provider_code:";
    private static final String OPENED = "opened";
    private static final String PAID = "paid";
    private static final String FAILED = "payment_failed";
    private static final String EXPIRED = "payment_expired";
    private static final List<String> KIND_ORDER = List.of(OPENED, PAID, FAILED, EXPIRED);

    private Timeline() {
    }

    /** One stored status move; {@code from} is null for the opening. */
    public record HistoryEntry(MoneyStatus from, MoneyStatus to, String reason, Instant occurredAt) {
    }

    /** One line of the timeline; {@code code} is the provider's code for a failure or an expiry, else null. */
    public record TimelineEvent(Instant at, String kind, String code) {
    }

    /** The listed events, and the code that explains how the payment ended when it ended without paying. */
    public record Composed(List<TimelineEvent> events, String outcomeCode) {
    }

    public static Composed compose(List<HistoryEntry> history) {
        List<TimelineEvent> events = history.stream()
                .map(Timeline::toEvent)
                .flatMap(Optional::stream)
                .sorted(Comparator.comparing(TimelineEvent::at)
                        .thenComparingInt(event -> KIND_ORDER.indexOf(event.kind())))
                .toList();
        String outcome = null;
        if (!events.isEmpty()) {
            TimelineEvent last = events.get(events.size() - 1);
            if (FAILED.equals(last.kind()) || EXPIRED.equals(last.kind())) {
                outcome = last.code();
            }
        }
        return new Composed(events, outcome);
    }

    private static Optional<TimelineEvent> toEvent(HistoryEntry entry) {
        return switch (entry.to()) {
            case PENDING -> OPENED.equals(entry.reason())
                    ? Optional.of(new TimelineEvent(entry.occurredAt(), OPENED, null))
                    : Optional.empty();
            case SUCCEEDED -> Optional.of(new TimelineEvent(entry.occurredAt(), PAID, null));
            case FAILED -> Optional.of(new TimelineEvent(entry.occurredAt(), FAILED, codeOf(entry.reason())));
            case EXPIRED -> Optional.of(new TimelineEvent(entry.occurredAt(), EXPIRED, codeOf(entry.reason())));
            case PARTIALLY_REFUNDED, REFUNDED, DISPUTED -> Optional.empty();
        };
    }

    private static String codeOf(String reason) {
        if (reason == null || !reason.startsWith(PROVIDER_CODE_PREFIX)) {
            return null;
        }
        String code = reason.substring(PROVIDER_CODE_PREFIX.length());
        return code.isBlank() ? null : code;
    }
}
