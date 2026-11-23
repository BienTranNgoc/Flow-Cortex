package com.flowcortex.user.application;

import com.flowcortex.user.api.CurrentUser;
import com.flowcortex.user.api.UserView;
import com.flowcortex.user.domain.AccountState;
import com.flowcortex.user.domain.AppUser;
import java.time.Instant;
import java.util.Map;

/**
 * Maps the internal {@link AppUser} entity to the {@code api} views. The one
 * place that mapping happens, so {@code passwordHash} has exactly one spot it
 * could leak from — and does not.
 */
final class AppUserMapper {

    private AppUserMapper() {
    }

    static CurrentUser toCurrentUser(AppUser user) {
        return new CurrentUser(
                user.getId(),
                user.getOwnerId(),
                user.getEmail(),
                user.getName(),
                user.getRole().code(),
                user.getStatus().name(),
                user.hasPassword(),
                user.getGoogleSubject() != null);
    }

    /** {@code roleNames} is the role catalog's code-to-name map; a code missing from it shows as itself. */
    static UserView toUserView(AppUser user, Map<String, String> roleNames, Instant now, boolean staffTarget) {
        String code = user.getRole().code();
        Instant lockedUntil = user.getLockedUntil();
        return new UserView(
                user.getId(),
                user.getOwnerId(),
                user.getEmail(),
                user.getName(),
                code,
                roleNames.getOrDefault(code, code),
                user.getStatus().name(),
                user.getCreatedAt(),
                user.getUpdatedAt(),
                user.getUsername(),
                AccountState.of(user.getStatus(), user.getEmailConfirmedAt(), lockedUntil, now).wire(),
                user.getEmailConfirmedAt(),
                lockedUntil != null && lockedUntil.isAfter(now) ? lockedUntil : null,
                UserView.signInMethodsOf(user.hasPassword(), user.getGoogleSubject() != null),
                user.isAdminDisabled(),
                staffTarget);
    }
}
