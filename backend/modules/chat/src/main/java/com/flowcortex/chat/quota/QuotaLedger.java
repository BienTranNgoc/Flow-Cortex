package com.flowcortex.chat.quota;

import java.time.LocalDate;
import java.util.List;
import java.util.OptionalInt;

/** Per-subject daily counters; what {@link ChatQuota} charges and refunds against. */
interface QuotaLedger {

    record Subject(String key, int limit) {
    }

    /**
     * Adds one to every subject for {@code day} in one atomic step.
     * @return empty when all were charged, else the index of the first subject already at its limit (none charged)
     */
    OptionalInt charge(LocalDate day, List<Subject> subjects);

    /** Takes one back from each key's counter of {@code day}, never below zero. */
    void refund(LocalDate day, List<String> keys);
}
