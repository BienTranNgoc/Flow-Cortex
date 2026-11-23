package com.flowcortex.user.api;

/** Account counts of one owner scope: {@code total} equals the four state counts added up. */
public record UserStats(long total, long active, long pendingConfirmation, long locked, long suspended,
        long newLast7Days) {
}
