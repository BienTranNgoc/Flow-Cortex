package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.credit.api.InsufficientCreditsException;
import com.flowcortex.shared.web.ConflictException;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.LocalizedStatusException;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.shared.web.UnprocessableEntityException;
import com.flowcortex.workflow.application.BatchRefusals.Action;
import com.flowcortex.workflow.application.BatchRefusals.Outcome;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class BatchRefusalsTest {

    @Test
    void insufficientCreditsPausesAndKeepsTheRowPending() {
        Outcome outcome = BatchRefusals.classify(new InsufficientCreditsException(0, 5), 0);

        assertThat(outcome.action()).isEqualTo(Action.PAUSE);
        assertThat(outcome.reason()).isEqualTo(BatchStatus.PAUSE_INSUFFICIENT_CREDITS);
        assertThat(outcome.countsAsStartFailure()).isFalse();
    }

    @Test
    void serviceUnavailablePausesAsUnavailable() {
        Throwable[] refusals = {
                new LocalizedStatusException(HttpStatus.SERVICE_UNAVAILABLE, new LocalizedMessage("workflow.disabled")),
                new LocalizedStatusException(HttpStatus.SERVICE_UNAVAILABLE, new LocalizedMessage("media.disabled")),
                new DubDisabledException()};

        for (Throwable refusal : refusals) {
            Outcome outcome = BatchRefusals.classify(refusal, 2);

            assertThat(outcome.action()).as(refusal.getMessage()).isEqualTo(Action.PAUSE);
            assertThat(outcome.reason()).isEqualTo(BatchStatus.PAUSE_UNAVAILABLE);
            assertThat(outcome.countsAsStartFailure()).isFalse();
        }
    }

    @Test
    void workflowNotFoundOrIncompleteCancelsAsWorkflowUnavailable() {
        Throwable[] refusals = {
                new NotFoundException(new LocalizedMessage("workflow.notFound", UUID.randomUUID())),
                new UnprocessableEntityException(new LocalizedMessage("workflow.incomplete", 1), "WORKFLOW_INCOMPLETE")};

        for (Throwable refusal : refusals) {
            Outcome outcome = BatchRefusals.classify(refusal, 0);

            assertThat(outcome.action()).as(refusal.getMessage()).isEqualTo(Action.CANCEL_BATCH);
            assertThat(outcome.reason()).isEqualTo(BatchStatus.CANCEL_WORKFLOW_UNAVAILABLE);
        }
    }

    @Test
    void anotherFourHundredFailsOnlyTheRowWithItsCodeAndAThreeHundredCharacterMessage() {
        String longArgument = "x".repeat(500);
        Outcome unprocessable = BatchRefusals.classify(new UnprocessableEntityException(
                new LocalizedMessage("workflow.run.inputTooLong", longArgument), "INPUT_REFUSED"), 0);
        Outcome conflict = BatchRefusals.classify(new ConflictException(new LocalizedMessage("workflow.conflict")), 0);
        Outcome status = BatchRefusals.classify(new LocalizedStatusException(HttpStatus.BAD_REQUEST,
                new LocalizedMessage("workflow.productVideo.startElsewhere")), 0);

        assertThat(unprocessable.action()).isEqualTo(Action.FAIL_ROW);
        assertThat(unprocessable.errorCode()).isEqualTo("INPUT_REFUSED");
        assertThat(unprocessable.errorMessage()).hasSize(300).startsWith("workflow.run.inputTooLong [xxx");
        assertThat(conflict.action()).isEqualTo(Action.FAIL_ROW);
        assertThat(conflict.errorCode()).isEqualTo("workflow.conflict");
        assertThat(conflict.errorMessage()).isEqualTo("workflow.conflict");
        assertThat(status.action()).isEqualTo(Action.FAIL_ROW);
        assertThat(status.errorCode()).isEqualTo("workflow.productVideo.startElsewhere");
        assertThat(status.countsAsStartFailure()).isFalse();
    }

    @Test
    void aStatusCarryingACodePropertyFailsTheRowWithThatCode() {
        LocalizedStatusException refusal = new LocalizedStatusException(HttpStatus.FORBIDDEN,
                new LocalizedMessage("workflow.forbidden"));
        refusal.getBody().setProperty("code", "FORBIDDEN_STEP");

        assertThat(BatchRefusals.classify(refusal, 0).errorCode()).isEqualTo("FORBIDDEN_STEP");
    }

    @Test
    void aServerErrorStatusIsTreatedAsUnexpected() {
        Outcome outcome = BatchRefusals.classify(new LocalizedStatusException(HttpStatus.BAD_GATEWAY,
                new LocalizedMessage("workflow.upstream")), 0);

        assertThat(outcome.action()).isEqualTo(Action.RETRY_ROW);
    }

    @Test
    void anUnexpectedErrorRetriesAndTheThirdFailsAsStartError() {
        RuntimeException hiccup = new IllegalStateException("store went away");

        Outcome first = BatchRefusals.classify(hiccup, 0);
        Outcome second = BatchRefusals.classify(hiccup, 1);
        Outcome third = BatchRefusals.classify(hiccup, 2);

        assertThat(first.action()).isEqualTo(Action.RETRY_ROW);
        assertThat(first.countsAsStartFailure()).isTrue();
        assertThat(second.action()).isEqualTo(Action.RETRY_ROW);
        assertThat(third.action()).isEqualTo(Action.FAIL_ROW);
        assertThat(third.errorCode()).isEqualTo("startError");
        assertThat(third.errorMessage()).isEqualTo("workflow.batch.startError");
    }

    @Test
    void anUnexpectedErrorNeverShowsItsOwnTextOnTheRow() {
        Outcome sql = BatchRefusals.classify(new IllegalStateException(
                "could not execute statement [insert into workflow_run (id) values (?)] on db-primary:5432"), 2);
        Outcome blank = BatchRefusals.classify(new NullPointerException(), 2);

        assertThat(sql.errorMessage()).isEqualTo("workflow.batch.startError");
        assertThat(blank.errorMessage()).isEqualTo("workflow.batch.startError");
    }
}
