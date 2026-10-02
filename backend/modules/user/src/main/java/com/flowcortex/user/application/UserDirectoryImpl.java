package com.flowcortex.user.application;

import com.flowcortex.user.api.CurrentUser;
import com.flowcortex.user.api.UserDirectory;
import com.flowcortex.user.infrastructure.AppUserRepository;
import com.flowcortex.user.infrastructure.UserEmail;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementation of {@link UserDirectory}. Not owner-scoped — see the interface javadoc for why.
 *
 * <p>Also the per-request suspension gate (via {@link AccountStatusGuard}): the shared identity
 * lookup behind both {@code AuthController.me} and {@code CurrentUserResolver.require}, so a
 * SUSPENDED row throws here — the same 403 {@link AccessDeniedException} {@code
 * AuthServiceImpl#login} throws — before either caller sees a {@link CurrentUser}. A JWT already
 * issued is never additionally revoked (no session store), but its bearer is refused on the
 * very next authenticated call once suspended.
 */
@Service
class UserDirectoryImpl implements UserDirectory {

    private final AppUserRepository userRepository;

    UserDirectoryImpl(AppUserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CurrentUser> findById(UUID id) {
        return userRepository.findById(id).map(user -> {
            AccountStatusGuard.requireActive(user);
            return AppUserMapper.toCurrentUser(user);
        });
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, String> findEmailsByIds(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return userRepository.findEmailsByIdIn(ids).stream().collect(Collectors.toMap(UserEmail::id, UserEmail::email));
    }
}
