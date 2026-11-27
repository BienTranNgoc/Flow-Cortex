package com.flowcortex.workflow.api;

/**
 * One text a member may edit in a step's result: {@code pointer} is a concrete JSON Pointer ({@code ""} is the
 * whole output), {@code index} and {@code source} are set for a line of a list (a translated segment), and
 * {@code text} is the current text, edited or original.
 */
public record EditableFieldView(String pointer, String label, Integer index, String source, String text) {
}
