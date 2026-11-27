package com.flowcortex.trash.domain;

import java.time.Duration;
import java.util.Optional;

/** The ranges of the retention and reminder-lead properties; each check answers the failure message, naming the property. */
public final class TrashLimits {

    private static final int MIN_RETENTION_DAYS = 1;
    private static final int MAX_RETENTION_DAYS = 365;
    private static final Duration MIN_LEAD = Duration.ofDays(1);

    private TrashLimits() {
    }

    public static Optional<String> checkRetention(Duration retention) {
        long days = retention.toDays();
        boolean wholeDays = retention.equals(Duration.ofDays(days));
        if (wholeDays && days >= MIN_RETENTION_DAYS && days <= MAX_RETENTION_DAYS) {
            return Optional.empty();
        }
        return Optional.of("flowcortex.trash.retention must be a whole number of days from "
                + MIN_RETENTION_DAYS + " to " + MAX_RETENTION_DAYS + ", was " + retention);
    }

    public static Optional<String> checkLead(Duration lead, Duration retention) {
        Duration longest = retention.minus(MIN_LEAD);
        if (lead.compareTo(MIN_LEAD) >= 0 && lead.compareTo(longest) <= 0) {
            return Optional.empty();
        }
        return Optional.of("flowcortex.trash.reminder-lead must be from 1 day to the retention minus 1 day ("
                + longest + "), was " + lead);
    }
}
