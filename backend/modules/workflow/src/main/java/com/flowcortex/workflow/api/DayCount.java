package com.flowcortex.workflow.api;

import java.time.LocalDate;

/** Runs started on one local day and how many of them ended in error. */
public record DayCount(LocalDate day, long runs, long failed) {
}
