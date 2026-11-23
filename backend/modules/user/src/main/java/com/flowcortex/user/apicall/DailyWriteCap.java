package com.flowcortex.user.apicall;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

/** The optional per-owner daily write cap: pure decisions over counts the caller read from the call rows. */
final class DailyWriteCap {

    private DailyWriteCap() {}

    /** Whether any limit can refuse this request, so a count is worth reading. */
    static boolean needsCount(int limitTotal, int limitCreate, boolean isWorkflowCreate) {
        return limitTotal > 0 || (isWorkflowCreate && limitCreate > 0);
    }

    /** The wait until 00:00 UTC when a limit is reached, empty when the write may run. */
    static Optional<Duration> decide(int limitTotal, int limitCreate, boolean isWorkflowCreate, long countTotal,
            long countCreate, Instant now) {
        boolean totalReached = limitTotal > 0 && countTotal >= limitTotal;
        boolean createReached = isWorkflowCreate && limitCreate > 0 && countCreate >= limitCreate;
        if (!totalReached && !createReached) {
            return Optional.empty();
        }
        return Optional.of(Duration.between(now, startOfDay(now).plus(Duration.ofDays(1))));
    }

    static Instant startOfDay(Instant now) {
        return now.atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant();
    }
}
