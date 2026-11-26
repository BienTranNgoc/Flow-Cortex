package com.flowcortex.workflow.application;

import com.flowcortex.credit.api.InsufficientCreditsException;
import com.flowcortex.shared.web.ConflictException;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.LocalizedStatusException;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.shared.web.UnprocessableEntityException;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import org.springframework.http.HttpStatus;

/**
 * Decides what a refused row start does to the row and its batch. An ordered table: the first row that matches
 * names the outcome, so a refusal that applies to every row of the batch is told apart from one that fails only
 * its own.
 */
final class BatchRefusals {

    /** What the dispatcher does with the claimed row and the batch. */
    enum Action {
        /** The row goes back to pending without a failure counted and the batch pauses with {@code reason}. */
        PAUSE,
        /** The batch is cancelled with {@code reason}; the row is resolved by its request key. */
        CANCEL_BATCH,
        /** Only the row fails, with {@code errorCode} and {@code errorMessage}. */
        FAIL_ROW,
        /** The row goes back to pending with one more start failure counted. */
        RETRY_ROW
    }

    /** The outcome of one refusal; {@code reason} is set for {@link Action#PAUSE} and {@link Action#CANCEL_BATCH}. */
    record Outcome(Action action, String reason, String errorCode, String errorMessage) {

        boolean countsAsStartFailure() {
            return action == Action.RETRY_ROW;
        }
    }

    static final String START_ERROR = "startError";
    /** The row shows this message code, never the exception's own text, which may carry SQL or host names. */
    static final String START_ERROR_MESSAGE = "workflow.batch.startError";
    static final int MAX_START_FAILURES = 3;

    private static final int MESSAGE_LIMIT = 300;
    private static final int CODE_LIMIT = 64;

    private record Rule(Predicate<Throwable> applies, Function<Throwable, Outcome> outcome) {
    }

    private static final List<Rule> RULES = List.of(
            new Rule(error -> error instanceof InsufficientCreditsException,
                    error -> pause(BatchStatus.PAUSE_INSUFFICIENT_CREDITS)),
            new Rule(error -> error instanceof LocalizedStatusException status
                    && status.getStatusCode().isSameCodeAs(HttpStatus.SERVICE_UNAVAILABLE),
                    error -> pause(BatchStatus.PAUSE_UNAVAILABLE)),
            new Rule(error -> error instanceof NotFoundException || isIncompleteWorkflow(error),
                    error -> new Outcome(Action.CANCEL_BATCH, BatchStatus.CANCEL_WORKFLOW_UNAVAILABLE, null, null)),
            new Rule(BatchRefusals::isClientRefusal, BatchRefusals::failRow));

    private BatchRefusals() {
    }

    /**
     * @param previousFailures start failures already counted on the row, before this one
     */
    static Outcome classify(Throwable refusal, int previousFailures) {
        for (Rule rule : RULES) {
            if (rule.applies().test(refusal)) {
                return rule.outcome().apply(refusal);
            }
        }
        if (previousFailures + 1 >= MAX_START_FAILURES) {
            return new Outcome(Action.FAIL_ROW, null, START_ERROR, START_ERROR_MESSAGE);
        }
        return new Outcome(Action.RETRY_ROW, null, null, null);
    }

    private static Outcome pause(String reason) {
        return new Outcome(Action.PAUSE, reason, null, null);
    }

    private static boolean isIncompleteWorkflow(Throwable error) {
        return error instanceof UnprocessableEntityException refused
                && WorkflowReadinessGuard.CODE.equals(refused.getCode());
    }

    private static boolean isClientRefusal(Throwable error) {
        return error instanceof UnprocessableEntityException || error instanceof ConflictException
                || error instanceof LocalizedStatusException status && status.getStatusCode().is4xxClientError();
    }

    private static Outcome failRow(Throwable error) {
        return new Outcome(Action.FAIL_ROW, null, truncate(codeOf(error), CODE_LIMIT),
                truncate(detailText(error), MESSAGE_LIMIT));
    }

    /** The refusal's bundle key plus its arguments, as a {@link LocalizedMessage} prints them. */
    private static String detailText(Throwable error) {
        if (error instanceof UnprocessableEntityException refused) {
            return refused.getDetail().toString();
        }
        if (error instanceof ConflictException conflict) {
            return conflict.getDetail().toString();
        }
        return ((LocalizedStatusException) error).getReason();
    }

    /** The machine code the refusal carries, else the bundle key of its detail. */
    private static String codeOf(Throwable error) {
        if (error instanceof UnprocessableEntityException refused && refused.getCode() != null) {
            return refused.getCode();
        }
        if (error instanceof LocalizedStatusException status
                && status.getBody().getProperties() != null
                && status.getBody().getProperties().get("code") instanceof String code) {
            return code;
        }
        if (error instanceof UnprocessableEntityException refused) {
            return refused.getDetail().code();
        }
        if (error instanceof ConflictException conflict) {
            return conflict.getDetail().code();
        }
        return ((LocalizedStatusException) error).getDetailMessageCode();
    }

    private static String truncate(String text, int limit) {
        if (text.length() <= limit) {
            return text;
        }
        int end = Character.isHighSurrogate(text.charAt(limit - 1)) ? limit - 1 : limit;
        return text.substring(0, end);
    }
}
