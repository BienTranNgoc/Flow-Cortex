package com.flowcortex.workflow.application;

/**
 * One step of a workflow definition, field for field what a copy carries: the served steps of a fork and the steps
 * of an imported artifact both arrive as this record. {@code config} is JSON text, or null when the step needs none.
 */
public record StepDefinition(int stepOrder, String name, String type, String config, String inputRef) {
}
