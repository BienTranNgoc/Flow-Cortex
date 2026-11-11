package com.flowcortex.user.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.flowcortex.shared.web.ConflictException;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.LocalizedProblem;
import com.flowcortex.shared.web.LocalizedStatusException;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.shared.web.UnprocessableEntityException;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;

class AuditErrorCodeTest {

    /** Stands in for SupportProblemException, whose module depends on this one. */
    private static final class SupportLikeProblem extends RuntimeException implements LocalizedProblem {
        private final LocalizedMessage detail;

        SupportLikeProblem(LocalizedMessage detail) {
            super(detail.toString());
            this.detail = detail;
        }

        @Override
        public LocalizedMessage localizedDetail() {
            return detail;
        }
    }

    @Test
    void aConflictGivesItsMessageKey() {
        ConflictException ex = new ConflictException(new LocalizedMessage("user.lastSuperAdmin"));

        assertThat(AuditErrorCode.of(ex, 409)).isEqualTo("user.lastSuperAdmin");
    }

    @Test
    void aNotFoundAndAnUnprocessableGiveTheirMessageKey() {
        assertThat(AuditErrorCode.of(new NotFoundException(new LocalizedMessage("workflow.notFound")), 404))
                .isEqualTo("workflow.notFound");
        assertThat(AuditErrorCode.of(
                        new UnprocessableEntityException(new LocalizedMessage("workflow.incomplete"), "x"), 422))
                .isEqualTo("workflow.incomplete");
    }

    @Test
    void aLocalizedProblemGivesItsKeyWithoutItsArguments() {
        SupportLikeProblem ex = new SupportLikeProblem(new LocalizedMessage("support.kb.groupNameTaken", "FAQ"));

        assertThat(AuditErrorCode.of(ex, 409)).isEqualTo("support.kb.groupNameTaken");
    }

    @Test
    void aLocalizedStatusExceptionGivesItsDetailMessageCode() {
        LocalizedStatusException ex =
                new LocalizedStatusException(HttpStatus.BAD_REQUEST, new LocalizedMessage("auth.tokenInvalid", "t"));

        assertThat(AuditErrorCode.of(ex, 400)).isEqualTo("auth.tokenInvalid");
    }

    @Test
    void validationFailuresGiveValidationFailed() throws Exception {
        MethodParameter parameter = new MethodParameter(AuditErrorCodeTest.class.getDeclaredMethod("target", String.class), 0);
        MethodArgumentNotValidException invalid =
                new MethodArgumentNotValidException(parameter, new BeanPropertyBindingResult(new Object(), "body"));
        HttpMessageNotReadableException unreadable =
                new HttpMessageNotReadableException("bad json", mock(HttpInputMessage.class));

        assertThat(AuditErrorCode.of(invalid, 400)).isEqualTo("validation-failed");
        assertThat(AuditErrorCode.of(unreadable, 400)).isEqualTo("validation-failed");
    }

    @Test
    void anAccessDeniedGivesForbidden() {
        assertThat(AuditErrorCode.of(new AccessDeniedException("no"), 403)).isEqualTo("forbidden");
    }

    @Test
    void anUnexpectedExceptionGivesItsClassNameNeverItsMessage() {
        String code = AuditErrorCode.of(new IllegalStateException("row 42 has secret abc"), 500);

        assertThat(code).isEqualTo("IllegalStateException");
    }

    @Test
    void noExceptionWithAnErrorStatusGivesHttpStatus() {
        assertThat(AuditErrorCode.of(null, 422)).isEqualTo("http-422");
    }

    @Test
    void aLongCodeIsCutTo120Characters() {
        ConflictException ex = new ConflictException(new LocalizedMessage("k".repeat(200)));

        assertThat(AuditErrorCode.of(ex, 409)).hasSize(120);
    }

    @SuppressWarnings("unused")
    private void target(String body) {}
}
