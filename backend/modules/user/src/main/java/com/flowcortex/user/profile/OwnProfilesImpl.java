package com.flowcortex.user.profile;

import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.user.api.OwnProfile;
import com.flowcortex.user.api.OwnProfiles;
import com.flowcortex.user.domain.AppUser;
import com.flowcortex.user.domain.UserStatus;
import com.flowcortex.user.infrastructure.AppUserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Each write runs in one transaction on the caller's row locked {@code FOR UPDATE}, after the status check. */
@Service
class OwnProfilesImpl implements OwnProfiles {

    private final AppUserRepository users;
    private final UsernameRules usernameRules;
    private final Clock clock;

    OwnProfilesImpl(AppUserRepository users, UsernameRules usernameRules, Clock clock) {
        this.users = users;
        this.usernameRules = usernameRules;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public OwnProfile get(UUID userId) {
        return view(requireActive(users.findById(userId), userId), clock.instant());
    }

    @Override
    @Transactional
    public OwnProfile updateNames(UUID userId, String name, String firstName, String lastName) {
        AppUser user = requireActive(users.findByIdForUpdate(userId), userId);
        user.changeNames(name.trim(), blankToNull(firstName), blankToNull(lastName));
        return view(user, clock.instant());
    }

    @Override
    @Transactional
    public OwnProfile changeUsername(UUID userId, String username) {
        AppUser user = requireActive(users.findByIdForUpdate(userId), userId);
        Instant now = clock.instant();
        usernameRules.change(user, username, now);
        return view(user, now);
    }

    // The status gate is repeated here because AccountStatusGuard is internal to the application package.
    private static AppUser requireActive(java.util.Optional<AppUser> found, UUID userId) {
        AppUser user = found.orElseThrow(() -> new NotFoundException(new LocalizedMessage("user.notFound", userId)));
        if (user.getStatus() == UserStatus.SUSPENDED) {
            throw new AccessDeniedException("Account is suspended");
        }
        return user;
    }

    private static OwnProfile view(AppUser user, Instant now) {
        Instant unlockAt = user.getUsernameChangedAt() == null ? null
                : user.getUsernameChangedAt().plus(UsernamePolicy.COOLDOWN);
        Instant nextChangeAt = unlockAt != null && unlockAt.isAfter(now) ? unlockAt : null;
        return new OwnProfile(user.getName(), user.getFirstName(), user.getLastName(), user.getUsername(),
                nextChangeAt);
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
