package com.flowcortex.user.totp;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.shared.web.LocaleConfig;
import com.flowcortex.shared.web.LocalizedStatusException;
import com.flowcortex.user.totp.TotpProblems.AlreadyEnabled;
import com.flowcortex.user.totp.TotpProblems.ChallengeInvalid;
import com.flowcortex.user.totp.TotpProblems.InvalidCode;
import com.flowcortex.user.totp.TotpProblems.Locked;
import com.flowcortex.user.totp.TotpProblems.NotEnabled;
import com.flowcortex.user.totp.TotpProblems.NotSetUp;
import com.flowcortex.user.totp.TotpProblems.RequiredForRole;
import com.flowcortex.user.totp.TotpProblems.Unavailable;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.LocaleResolver;

class TotpProblemsTest {

    private static final Instant UNTIL = Instant.parse("2026-11-24T10:15:00Z");

    private ProblemDetail lockedFor(String acceptLanguage) {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(LocaleConfig.class)) {
            TotpProblems advice = new TotpProblems(context.getBean(MessageSource.class),
                    context.getBean(LocaleResolver.class));
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader("Accept-Language", acceptLanguage);
            return advice.handleLocked(new Locked(UNTIL), request);
        }
    }

    @Test
    void lockedCarriesItsEndInstantAndTheTimeInTheRequestsLanguage() {
        ProblemDetail english = lockedFor("en");
        ProblemDetail vietnamese = lockedFor("vi");

        assertThat(english.getStatus()).isEqualTo(HttpStatus.LOCKED.value());
        assertThat(english.getProperties()).containsEntry("code", "TOTP_LOCKED")
                .containsEntry("lockedUntil", "2026-11-24T10:15:00Z");
        assertThat(english.getDetail()).startsWith("Too many wrong codes. Try again after").contains("2026")
                .endsWith("UTC.");
        assertThat(vietnamese.getDetail()).startsWith("Nhập sai quá nhiều lần. Thử lại sau").endsWith("UTC.");
    }

    @Test
    void everyOtherProblemHasItsStatusAndStableCodeAndAnAnswerInBothLanguages() {
        List<Object[]> expected = List.of(
                new Object[] {new AlreadyEnabled(), HttpStatus.CONFLICT, "TOTP_ALREADY_ENABLED"},
                new Object[] {new NotSetUp(), HttpStatus.CONFLICT, "TOTP_NOT_SET_UP"},
                new Object[] {new NotEnabled(), HttpStatus.CONFLICT, "TOTP_NOT_ENABLED"},
                new Object[] {new RequiredForRole(), HttpStatus.CONFLICT, "TOTP_REQUIRED_FOR_ROLE"},
                new Object[] {new InvalidCode(), HttpStatus.UNPROCESSABLE_CONTENT, "TOTP_INVALID_CODE"},
                new Object[] {new ChallengeInvalid(), HttpStatus.UNAUTHORIZED, "TOTP_CHALLENGE_INVALID"},
                new Object[] {new Unavailable(), HttpStatus.SERVICE_UNAVAILABLE, "TOTP_UNAVAILABLE"});
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(LocaleConfig.class)) {
            MessageSource messages = context.getBean(MessageSource.class);
            for (Object[] row : expected) {
                LocalizedStatusException problem = (LocalizedStatusException) row[0];
                assertThat(problem.getStatusCode()).isEqualTo(row[1]);
                assertThat(problem.getBody().getProperties()).containsEntry("code", row[2]);
                assertThat(messages.getMessage(problem.getDetailMessageCode(), null, Locale.ENGLISH)).isNotBlank();
                assertThat(messages.getMessage(problem.getDetailMessageCode(), null, Locale.of("vi"))).isNotBlank();
            }
        }
    }
}
