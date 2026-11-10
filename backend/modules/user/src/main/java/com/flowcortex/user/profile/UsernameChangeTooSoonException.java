package com.flowcortex.user.profile;

import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.UnprocessableEntityException;
import java.time.Instant;
import java.util.Map;

/**
 * A second username change inside the cooldown. Answered as a 422 by {@code UsernameProblemHandler},
 * which renders the unlock date in the caller's language and adds {@code nextChangeAt}.
 */
public class UsernameChangeTooSoonException extends UnprocessableEntityException {

    public static final String CODE = "USERNAME_CHANGE_TOO_SOON";
    public static final String MESSAGE_KEY = "profile.username.changeTooSoon";

    private final Instant nextChangeAt;

    public UsernameChangeTooSoonException(Instant nextChangeAt) {
        super(new LocalizedMessage("profile.username.changeTooSoon", nextChangeAt), CODE,
                Map.of("username", new LocalizedMessage("profile.username.changeTooSoon", nextChangeAt)));
        this.nextChangeAt = nextChangeAt;
    }

    public Instant getNextChangeAt() {
        return nextChangeAt;
    }
}
