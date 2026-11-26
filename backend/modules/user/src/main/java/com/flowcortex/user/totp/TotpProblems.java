package com.flowcortex.user.totp;

import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.LocalizedStatusException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;
import org.springframework.context.MessageSource;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.LocaleResolver;

/**
 * The problems the second factor answers with. Each exception carries a stable machine {@code code} a client
 * branches on; every other one is rendered by the shared handler from its localized detail. The lock refusal is
 * answered here so its end time is written in the request's language, next to the {@code lockedUntil} instant.
 * It is ordered first because the shared handler would otherwise claim the subclass too.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TotpProblems {

    private static final String TYPE_BASE = "https://flow-cortex.dev/problems/";

    private final MessageSource messages;
    private final LocaleResolver localeResolver;

    TotpProblems(MessageSource messages, LocaleResolver localeResolver) {
        this.messages = messages;
        this.localeResolver = localeResolver;
    }

    @ExceptionHandler(Locked.class)
    ProblemDetail handleLocked(Locked ex, HttpServletRequest request) {
        Locale locale = localeResolver.resolveLocale(request);
        String until = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(locale)
                .withZone(ZoneOffset.UTC).format(ex.lockedUntil()) + " UTC";
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.LOCKED,
                messages.getMessage("auth.totp.locked", new Object[] {until}, locale));
        detail.setType(URI.create(TYPE_BASE + "totp-locked"));
        detail.setProperty("code", Locked.CODE);
        detail.setProperty("lockedUntil", ex.lockedUntil().toString());
        return detail;
    }

    abstract static class TotpProblem extends LocalizedStatusException {

        TotpProblem(HttpStatus status, LocalizedMessage message, String slug, String code) {
            super(status, message);
            setType(URI.create(TYPE_BASE + slug));
            getBody().setProperty("code", code);
        }
    }

    /** 409: the account already has TOTP on. */
    public static final class AlreadyEnabled extends TotpProblem {
        AlreadyEnabled() {
            super(HttpStatus.CONFLICT, new LocalizedMessage("auth.totp.alreadyEnabled"), "totp-already-enabled",
                    "TOTP_ALREADY_ENABLED");
        }
    }

    /** 409: no pending setup to confirm. */
    public static final class NotSetUp extends TotpProblem {
        NotSetUp() {
            super(HttpStatus.CONFLICT, new LocalizedMessage("auth.totp.notSetUp"), "totp-not-set-up",
                    "TOTP_NOT_SET_UP");
        }
    }

    /** 409: the account has TOTP off. */
    public static final class NotEnabled extends TotpProblem {
        public NotEnabled() {
            super(HttpStatus.CONFLICT, new LocalizedMessage("auth.totp.notEnabled"), "totp-not-enabled",
                    "TOTP_NOT_ENABLED");
        }
    }

    /** 409: a console account cannot turn TOTP off while the requirement is on. */
    public static final class RequiredForRole extends TotpProblem {
        RequiredForRole() {
            super(HttpStatus.CONFLICT, new LocalizedMessage("auth.totp.requiredForRole"), "totp-required-for-role",
                    "TOTP_REQUIRED_FOR_ROLE");
        }
    }

    /** 422: the code is wrong, replayed or of the wrong kind for this action. Counted toward the lock. */
    public static final class InvalidCode extends TotpProblem {
        public InvalidCode() {
            super(HttpStatus.UNPROCESSABLE_CONTENT, new LocalizedMessage("auth.totp.invalidCode"), "totp-invalid-code",
                    "TOTP_INVALID_CODE");
        }
    }

    /** 423: the second factor is locked until {@link #lockedUntil()}; nothing was examined or counted. */
    public static final class Locked extends TotpProblem {

        static final String CODE = "TOTP_LOCKED";

        private final Instant lockedUntil;

        Locked(Instant lockedUntil) {
            super(HttpStatus.LOCKED, new LocalizedMessage("auth.totp.locked"), "totp-locked", CODE);
            this.lockedUntil = lockedUntil;
        }

        public Instant lockedUntil() {
            return lockedUntil;
        }
    }

    /** 401: the sign-in step is unknown, expired, spent, dead, or its account no longer has TOTP on. */
    public static final class ChallengeInvalid extends TotpProblem {
        public ChallengeInvalid() {
            super(HttpStatus.UNAUTHORIZED, new LocalizedMessage("auth.totp.challengeInvalid"), "totp-challenge-invalid",
                    "TOTP_CHALLENGE_INVALID");
        }
    }

    /** 503: the stored secret cannot be opened with the keyring; an administrator resets the account. */
    public static final class Unavailable extends TotpProblem {
        Unavailable() {
            super(HttpStatus.SERVICE_UNAVAILABLE, new LocalizedMessage("auth.totp.unavailable"), "totp-unavailable",
                    "TOTP_UNAVAILABLE");
        }
    }
}
