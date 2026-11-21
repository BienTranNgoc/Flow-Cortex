package com.flowcortex.workflow.web;

/**
 * Optional body of a run retry. {@code fromStepOrder} is the 1-based step the new run starts
 * re-executing from; omitted, the first step that did not complete. Range is checked by the retry
 * rules, which answer 422 with an {@code errors.fromStepOrder} entry. {@code acknowledgeCost} is the
 * member's confirmation of the re-run's cost; absent means not confirmed.
 */
record RetryRunRequest(Integer fromStepOrder, Boolean acknowledgeCost) {
}
