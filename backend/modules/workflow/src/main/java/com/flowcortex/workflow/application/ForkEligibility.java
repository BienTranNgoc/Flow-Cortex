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
        OWN, FIXED, NOT_ALLOWED
    }

    private record Subject(UUID sourceId, UUID sourceOwnerId, boolean allowFork, UUID callerId) {
    }

    private record Rule(Predicate<Subject> applies, Refusal refusal) {
    }

    private static final List<Rule> RULES = List.of(
            new Rule(subject -> subject.sourceOwnerId().equals(subject.callerId()), Refusal.OWN),
            new Rule(subject -> ProductVideoWorkflow.ID.equals(subject.sourceId()), Refusal.FIXED),
            new Rule(subject -> !subject.allowFork(), Refusal.NOT_ALLOWED));

    private ForkEligibility() {
    }

    static Optional<Refusal> refusal(UUID sourceId, UUID sourceOwnerId, boolean allowFork, UUID callerId) {
        Subject subject = new Subject(sourceId, sourceOwnerId, allowFork, callerId);
        return RULES.stream().filter(rule -> rule.applies().test(subject)).map(Rule::refusal).findFirst();
    }
}
