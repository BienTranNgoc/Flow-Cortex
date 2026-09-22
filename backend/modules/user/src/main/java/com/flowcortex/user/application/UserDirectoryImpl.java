package com.flowcortex.user.application;

import com.flowcortex.user.api.CurrentUser;
import com.flowcortex.user.api.UserDirectory;
import com.flowcortex.user.infrastructure.AppUserRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementation of {@link UserDirectory} (design.md D2). Not owner-scoped —
 * see the interface javadoc for why that is safe.
 *
 * <p>Also the per-request suspension gate (R-021/R-023, via {@link
 * AccountStatusGuard}): this is the shared identity lookup behind both
 * {@code AuthController.me} and {@code CurrentUserResolver.require}, so a
 * SUSPENDED row throws here — the same 403 {@link AccessDeniedException}
 * {@code AuthServiceImpl#login} throws for suspension — before either caller
 * ever sees a {@link CurrentUser}. A JWT already issued is never
 * additionally revoked (no session store, design.md scope), but its bearer
 * is refused on the very next authenticated call once their row is
 * suspended.
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
}
