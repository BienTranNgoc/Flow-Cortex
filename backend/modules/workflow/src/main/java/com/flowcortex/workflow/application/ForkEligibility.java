package com.flowcortex.workflow.application;

import com.flowcortex.workflow.api.ProductVideoWorkflow;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Decides whether a caller who can see a workflow may fork it. An ordered rule table, the first row that matches
 * names the refusal: the owner learns "this is yours" before any switch is looked at.
 */
final class ForkEligibility {

    /** Why a fork is refused. */
    enum Refusal {
        OWN, FIXED, NOT_ALLOWED, CONNECTOR_STEP
    }

    private record Subject(UUID sourceId, UUID sourceOwnerId, boolean allowFork, boolean holdsConnectorCall,
            UUID callerId) {
    }

    private record Rule(Predicate<Subject> applies, Refusal refusal) {
    }

    private static final List<Rule> RULES = List.of(
            new Rule(subject -> subject.sourceOwnerId().equals(subject.callerId()), Refusal.OWN),
            new Rule(subject -> ProductVideoWorkflow.isProductVideo(subject.sourceId()), Refusal.FIXED),
            new Rule(subject -> !subject.allowFork(), Refusal.NOT_ALLOWED),
            new Rule(Subject::holdsConnectorCall, Refusal.CONNECTOR_STEP));

    private ForkEligibility() {
    }

    /**
     * A step that reads a member connector (a connector call or a drive read) runs with the credential of whoever
     * owns the workflow, and a copy is owned by the caller: it would act on a request or folder someone else chose,
     * so a workflow that holds one is not copied. {@code holdsConnectorCall} is true for any such step.
     */
    static Optional<Refusal> refusal(UUID sourceId, UUID sourceOwnerId, boolean allowFork,
            boolean holdsConnectorCall, UUID callerId) {
        Subject subject = new Subject(sourceId, sourceOwnerId, allowFork, holdsConnectorCall, callerId);
        return RULES.stream().filter(rule -> rule.applies().test(subject)).map(Rule::refusal).findFirst();
    }
}
