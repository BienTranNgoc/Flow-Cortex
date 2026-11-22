package com.flowcortex.workflow.application;

import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.workflow.application.OutboundTargets.Target;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Grades a parsed artifact from the faults the authoring validator and the run-start readiness check report for its
 * steps. What authoring refuses is blocking: a review that is not blocked is a write that succeeds. What run start
 * refuses is advisory, since a draft may legally hold it.
 *
 * <p>Each issue's message key is {@code workflow.artifact.issue.<code>} with the step order as its first argument
 * ({@code reference_not_earlier} adds the referenced step as its second); a readiness reason without a code of its
 * own travels under {@code run_start_reason} carrying its own message.
 */
final class ImportGrading {

    enum Severity {
        BLOCKING, ADVISORY
    }

    /** {@code stepOrder} and {@code field} are null for a fault about the workflow as a whole. */
    record Issue(String code, Severity severity, Integer stepOrder, String field, LocalizedMessage message) {
    }

    record Review(List<Issue> issues, List<Target> targets, boolean blocked, boolean outbound) {
    }

    private static final Pattern DEFINITION_PATH = Pattern.compile("^steps\\[(\\d+)]\\.(\\w+)$");

    private ImportGrading() {
    }

    /**
     * @param definitionFaults what authoring refuses, keyed by property path ({@code steps[i].type}, {@code steps[i].config})
     * @param readinessFaults what run start refuses, keyed by step order ({@code 0} for a workflow without steps)
     */
    static Review grade(WorkflowArtifact artifact, Map<String, String> definitionFaults,
            Map<Integer, LocalizedMessage> readinessFaults, List<Target> targets) {
        List<Issue> issues = new ArrayList<>();
        definitionFaults.keySet().forEach(path -> issues.add(definitionIssue(artifact, path)));
        readinessFaults.forEach((order, reason) -> issues.add(readinessIssue(order, reason)));
        issues.sort(Comparator.comparingInt(issue -> issue.stepOrder() == null ? 0 : issue.stepOrder()));
        boolean blocked = issues.stream().anyMatch(issue -> issue.severity() == Severity.BLOCKING);
        return new Review(List.copyOf(issues), List.copyOf(targets), blocked, !targets.isEmpty());
    }

    private static Issue definitionIssue(WorkflowArtifact artifact, String path) {
        Matcher match = DEFINITION_PATH.matcher(path);
        if (!match.matches()) {
            return blocking("step_config_invalid", null, path);
        }
        int index = Integer.parseInt(match.group(1));
        Integer order = index < artifact.steps().size() ? artifact.steps().get(index).stepOrder() : null;
        String field = match.group(2);
        return blocking("type".equals(field) ? "step_type_unavailable" : "step_config_invalid", order, field);
    }

    private static Issue blocking(String code, Integer order, String field) {
        return new Issue(code, Severity.BLOCKING, order, field, issueMessage(code, order));
    }

    private static Issue readinessIssue(int order, LocalizedMessage reason) {
        Integer stepOrder = order == 0 ? null : order;
        return switch (reason.code()) {
            case "workflow.incomplete.noSteps" -> advisory("no_steps", null, null, issueMessage("no_steps", null));
            case "workflow.incomplete.profileEmpty" ->
                    advisory("profile_empty", stepOrder, "config", issueMessage("profile_empty", stepOrder));
            case "workflow.incomplete.notEarlierStep" -> advisory("reference_not_earlier", stepOrder, "inputRef",
                    new LocalizedMessage("workflow.artifact.issue.reference_not_earlier", order,
                            reason.args().length > 0 ? reason.args()[0] : ""));
            default -> advisory("run_start_reason", stepOrder, null, reason);
        };
    }

    private static Issue advisory(String code, Integer order, String field, LocalizedMessage message) {
        return new Issue(code, Severity.ADVISORY, order, field, message);
    }

    /** One literal per code, so the bundle parity check can see every key this class names. */
    private static LocalizedMessage issueMessage(String code, Integer order) {
        Object[] args = order == null ? new Object[0] : new Object[] {order};
        return switch (code) {
            case "step_type_unavailable" -> new LocalizedMessage("workflow.artifact.issue.step_type_unavailable", args);
            case "step_config_invalid" -> new LocalizedMessage("workflow.artifact.issue.step_config_invalid", args);
            case "no_steps" -> new LocalizedMessage("workflow.artifact.issue.no_steps", args);
            case "profile_empty" -> new LocalizedMessage("workflow.artifact.issue.profile_empty", args);
            default -> throw new IllegalArgumentException("no message for issue code " + code);
        };
    }
}
