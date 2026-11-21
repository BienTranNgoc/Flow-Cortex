package com.flowcortex.workflow.api.runhistory;

import java.time.LocalDate;

/** Runs started on one local day, how many ended in error and their net credits ({@code null} while credits are off). */
public record UsageDay(LocalDate day, long runs, long failed, Long credits) {
}
