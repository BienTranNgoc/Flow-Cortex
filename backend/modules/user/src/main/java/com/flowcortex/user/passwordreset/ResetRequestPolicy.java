package com.flowcortex.user.passwordreset;

import com.flowcortex.user.domain.AppUser;
import com.flowcortex.user.domain.UserStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Who gets what when a password reset is asked for: the first rule that applies to the account wins.
 * A locked account is not a rule of its own, because a reset is how it gets out of the lockout.
 */
public final class ResetRequestPolicy {

    public sealed interface Outcome permits ResetLink, ExistingLink, GoogleNotice, Skip {
    }

    /** A new reset link for an account that signs in with a password. */
    public record ResetLink(UUID userId) implements Outcome {
    }

    /** The link "resend confirmation" would send: confirmation when unconfirmed, else set-password. */
    public record ExistingLink(UUID userId) implements Outcome {
    }

    /** A notice that the account signs in with Google, and no link. */
    public record GoogleNotice(UUID userId) implements Outcome {
    }

    /** Nothing is sent; {@code reason} is a log value. */
    public record Skip(String reason) implements Outcome {
    }

    private record Rule(Predicate<AppUser> applies, Function<AppUser, Outcome> outcome) {
    }

    private static final List<Rule> RULES = List.of(
            new Rule(user -> user.getStatus() != UserStatus.ACTIVE, user -> new Skip("inactive")),
            new Rule(user -> !user.isEmailConfirmed(), user -> new ExistingLink(user.getId())),
            new Rule(AppUser::hasPassword, user -> new ResetLink(user.getId())),
            new Rule(user -> user.getGoogleSubject() != null, user -> new GoogleNotice(user.getId())),
            new Rule(user -> true, user -> new ExistingLink(user.getId())));

    private ResetRequestPolicy() {
    }

    public static Outcome decide(Optional<AppUser> account) {
        if (account.isEmpty()) {
            return new Skip("unknown_email");
        }
        AppUser user = account.get();
        return RULES.stream()
                .filter(rule -> rule.applies().test(user))
                .findFirst()
                .orElseThrow()
                .outcome()
                .apply(user);
    }
}
