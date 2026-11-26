package com.flowcortex.user.accountdeletion;

import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.LocalizedStatusException;
import com.flowcortex.user.accountdeletion.DeletionEligibility.Reason;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * The refusals of the account deletion routes and gate. Each carries its machine {@code code} as a
 * problem property; the detail is a bundle key the global handler resolves in the request's language.
 */
final class AccountDeletionProblems {

    static final String PENDING = "ACCOUNT_DELETION_PENDING";
    static final String STAFF_ACCOUNT = "account.deletion.staffAccount";
    static final String BLOCKED = "account.deletion.blocked";
    static final String ELIGIBILITY_UNAVAILABLE = "account.deletion.eligibilityUnavailable";
    static final String RECOVERY_EXPIRED = "account.deletion.recoveryExpired";
    static final String NOT_PENDING = "account.deletion.notPending";

    private static final URI BASE = URI.create("https://flow-cortex.dev/problems/");

    private AccountDeletionProblems() {
    }

    /** 403: the gate holds a pending or expired account. */
    static LocalizedStatusException pending() {
        return problem(HttpStatus.FORBIDDEN, "forbidden", new LocalizedMessage("account.deletion.pending"), PENDING);
    }

    /** 503: the switch is off, so nothing is read or spent. */
    static LocalizedStatusException turnedOff() {
        return new LocalizedStatusException(HttpStatus.SERVICE_UNAVAILABLE, new LocalizedMessage("feature.turnedOff"));
    }

    /** 409: only an end-user role may delete its own account. */
    static LocalizedStatusException staffAccount() {
        return problem(HttpStatus.CONFLICT, "conflict", new LocalizedMessage("account.deletion.staffAccount"),
                STAFF_ACCOUNT);
    }

    /** 409: every matching condition travels as {@code reasons}. */
    static LocalizedStatusException blocked(List<Reason> reasons) {
        LocalizedStatusException problem =
                problem(HttpStatus.CONFLICT, "conflict", new LocalizedMessage("account.deletion.blocked"), BLOCKED);
        problem.getBody().setProperty("reasons", reasons.stream().map(AccountDeletionProblems::reasonOf).toList());
        return problem;
    }

    /** 503: a condition could not be read, so the account is never reported eligible. */
    static LocalizedStatusException eligibilityUnavailable() {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "service-unavailable",
                new LocalizedMessage("account.deletion.eligibilityUnavailable"), ELIGIBILITY_UNAVAILABLE);
    }

    /** 422: the deadline has passed. */
    static LocalizedStatusException recoveryExpired() {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "unprocessable",
                new LocalizedMessage("account.deletion.recoveryExpired"), RECOVERY_EXPIRED);
    }

    /** 409: there is no pending request to cancel. */
    static LocalizedStatusException notPending() {
        return problem(HttpStatus.CONFLICT, "conflict", new LocalizedMessage("account.deletion.notPending"),
                NOT_PENDING);
    }

    private static LocalizedStatusException problem(HttpStatus status, String typeSlug, LocalizedMessage detail,
            String code) {
        LocalizedStatusException problem = new LocalizedStatusException(status, detail);
        problem.setType(BASE.resolve(typeSlug));
        problem.getBody().setProperty("code", code);
        return problem;
    }

    private static Map<String, String> reasonOf(Reason reason) {
        Map<String, String> view = new LinkedHashMap<>();
        view.put("key", reason.key());
        view.put("message", reason.message());
        return view;
    }
}
