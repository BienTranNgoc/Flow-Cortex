package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.credit.api.InsufficientCreditsException;
import com.flowcortex.media.api.UploadRejectedException;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.LocalizedStatusException;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.shared.web.UnprocessableEntityException;
import com.flowcortex.workflow.application.ScheduleFireErrors.Described;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ScheduleFireErrorsTest {

    @Test
    void insufficientCreditsIsItsCodeWithItsKeyAndArgs() {
        Described described = ScheduleFireErrors.describe(new InsufficientCreditsException(2, 9));

        assertThat(described.code()).isEqualTo("INSUFFICIENT_CREDITS");
        assertThat(described.messageKey()).isEqualTo("credits.insufficient");
        assertThat(described.args()).containsExactly(2L, 9L, 7L);
    }

    @Test
    void localizedStatusIsItsMessageKeyWithArgs() {
        Described disabled = ScheduleFireErrors.describe(
                new LocalizedStatusException(HttpStatus.SERVICE_UNAVAILABLE, new LocalizedMessage("workflow.disabled")));
        Described media = ScheduleFireErrors.describe(
                new LocalizedStatusException(HttpStatus.SERVICE_UNAVAILABLE, new LocalizedMessage("media.disabled")));

        assertThat(disabled).isEqualTo(new Described("workflow.disabled", "workflow.disabled", List.of()));
        assertThat(media).isEqualTo(new Described("media.disabled", "media.disabled", List.of()));
    }

    @Test
    void aLocalizedStatusThatCarriesItsOwnProblemCodeKeepsIt() {
        Described described = ScheduleFireErrors.describe(new DubDisabledException());

        assertThat(described.code()).isEqualTo("dub_disabled");
        assertThat(described.messageKey()).isEqualTo("workflow.dub.disabled");
    }

    @Test
    void unprocessableIsItsCodeAndDetailKey() {
        Described described = ScheduleFireErrors.describe(
                new UnprocessableEntityException(new LocalizedMessage("workflow.incomplete", 2), "WORKFLOW_INCOMPLETE"));

        assertThat(described).isEqualTo(new Described("WORKFLOW_INCOMPLETE", "workflow.incomplete", List.of(2)));
    }

    @Test
    void unprocessableWithoutACodeFallsBackToItsKey() {
        Described described = ScheduleFireErrors.describe(
                new UnprocessableEntityException(new LocalizedMessage("workflow.something"), null));

        assertThat(described).isEqualTo(new Described("workflow.something", "workflow.something", List.of()));
    }

    @Test
    void notFoundIsWorkflowNotFound() {
        UUID workflowId = UUID.randomUUID();

        Described described = ScheduleFireErrors.describe(
                new NotFoundException(new LocalizedMessage("workflow.notFound", workflowId)));

        assertThat(described)
                .isEqualTo(new Described("workflow.notFound", "workflow.notFound", List.of(workflowId.toString())));
    }

    @Test
    void anUploadRefusalIsItsDetailKey() {
        Described described = ScheduleFireErrors.describe(
                new UploadRejectedException("uploadId", new LocalizedMessage("upload.mediaRequired")));

        assertThat(described).isEqualTo(new Described("upload.mediaRequired", "upload.mediaRequired", List.of()));
    }

    @Test
    void anyOtherIsStartErrorWithTheStartFailedKey() {
        Described described = ScheduleFireErrors.describe(new IllegalStateException("connection reset by peer"));

        assertThat(described).isEqualTo(new Described("startError", "workflow.schedule.startFailed", List.of()));
    }

    @Test
    void argsAreStringsAndNumbersOnly() {
        UUID id = UUID.randomUUID();
        Object other = new Object() {
            @Override
            public String toString() {
                return "custom";
            }
        };

        Described described = ScheduleFireErrors.describe(new UnprocessableEntityException(
                new LocalizedMessage("workflow.incomplete", "step", 3, 4.5, id, other), "WORKFLOW_INCOMPLETE"));

        assertThat(described.args()).containsExactly("step", 3, 4.5, id.toString(), "custom");
    }
}
