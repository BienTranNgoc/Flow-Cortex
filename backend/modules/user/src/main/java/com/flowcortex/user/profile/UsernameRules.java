package com.flowcortex.user.profile;

import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.UnprocessableEntityException;
import com.flowcortex.user.domain.AppUser;
import com.flowcortex.user.emailconfirm.RegistrationConflictException;
import com.flowcortex.user.infrastructure.AppUserRepository;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * Every username write goes through here, inside the caller's transaction: the policy decides, then
 * availability (a taken name or another member's unexpired hold) is checked under a per-name advisory
 * lock, so two claims of one free name end with one owner. Lock order is the caller's row first, the
 * name second.
 */
@Component
public class UsernameRules {

    public static final String NOT_ALLOWED_CODE = "USERNAME_NOT_ALLOWED";

    private static final Logger LOG = LoggerFactory.getLogger(UsernameRules.class);
    private static final String USERNAME_UNIQUE_INDEX = "uq_app_user_username";

    private final AppUserRepository users;
    private final UsernameHoldRepository holds;

    UsernameRules(AppUserRepository users, UsernameHoldRepository holds) {
        this.users = users;
        this.holds = holds;
    }

    /**
     * Changes the username of {@code lockedUser}, whose row the caller already holds {@code FOR UPDATE}.
     * Re-sending the stored name does nothing.
     *
     * @throws UsernameChangeTooSoonException inside the cooldown
     * @throws UnprocessableEntityException   when the policy blocks the name
     * @throws RegistrationConflictException  when another account holds the name or has it on hold
     */
    public void change(AppUser lockedUser, String next, Instant now) {
        users.lockUsername(next);
        UsernamePolicy.Decision decision = UsernamePolicy.decide(new UsernamePolicy.Change(
                lockedUser.getUsername(), next, lockedUser.getUsernameChangedAt(), now));
        switch (decision) {
            case UsernamePolicy.Decision.Noop noop -> { }
            case UsernamePolicy.Decision.Refuse refuse -> throw refusal(refuse);
            case UsernamePolicy.Decision.Proceed proceed -> apply(lockedUser, next, now, proceed);
        }
    }

    /** Registration and first password setup: a blocked or held name is refused before any account row is written. */
    public void assertAllowedForNewAccount(String name, Instant now) {
        users.lockUsername(name);
        if (UsernamePolicy.classify(name) instanceof UsernamePolicy.TextVerdict.Blocked blocked) {
            throw notAllowed(blocked.group());
        }
        if (holds.findActive(name, now).isPresent()) {
            throw RegistrationConflictException.usernameTaken();
        }
    }

    private void apply(AppUser user, String next, Instant now, UsernamePolicy.Decision.Proceed proceed) {
        if (users.existsByUsernameAndIdNot(next, user.getId())
                || holds.findActiveForOther(next, user.getId(), now).isPresent()) {
            throw RegistrationConflictException.usernameTaken();
        }
        String old = user.getUsername();
        user.changeUsername(next, proceed.countsAsChange() ? now : null);
        try {
            users.saveAndFlush(user);
        } catch (DataIntegrityViolationException ex) {
            if (isUsernameTaken(ex)) {
                throw RegistrationConflictException.usernameTaken();
            }
            throw ex;
        }
        if (proceed.holdOld() && old != null) {
            holds.upsert(old, user.getId(), now.plus(UsernamePolicy.COOLDOWN), now);
        }
        holds.deleteByUsername(next);
    }

    private static RuntimeException refusal(UsernamePolicy.Decision.Refuse refuse) {
        return switch (refuse.code()) {
            case CHANGE_TOO_SOON -> new UsernameChangeTooSoonException(refuse.nextChangeAt());
            case NOT_ALLOWED -> notAllowed(refuse.group());
        };
    }

    private static UnprocessableEntityException notAllowed(String group) {
        LOG.info("Username refused by policy: group={}", group);
        LocalizedMessage message = new LocalizedMessage("profile.username.notAllowed");
        return new UnprocessableEntityException(message, NOT_ALLOWED_CODE, Map.of("username", message));
    }

    private static boolean isUsernameTaken(DataIntegrityViolationException ex) {
        String message = NestedExceptionUtils.getMostSpecificCause(ex).getMessage();
        return message != null && message.contains(USERNAME_UNIQUE_INDEX);
    }
}
