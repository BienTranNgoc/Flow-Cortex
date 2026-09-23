package com.flowcortex.user.application;

import com.flowcortex.shared.web.ConflictException;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.user.api.UserService;
import com.flowcortex.user.api.UserView;
import com.flowcortex.user.domain.AppUser;
import com.flowcortex.user.domain.Role;
import com.flowcortex.user.domain.UserStatus;
import com.flowcortex.user.infrastructure.AppUserRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementation of {@link UserService}.
 *
 * <p>Duplicate-email safety is NOT a check-then-insert (that races): every
 * write flushes immediately ({@code saveAndFlush}) so a violation of the
 * database's {@code UNIQUE (email)} constraint surfaces synchronously, right
 * here, as a {@link DataIntegrityViolationException} translated to {@link
 * ConflictException} (409) — the database is the sole arbiter of
 * uniqueness.
 */
@Service
class UserServiceImpl implements UserService {

    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    UserServiceImpl(AppUserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public UserView createInitialAdmin(String email, String rawPassword, String name) {
        UUID id = UUID.randomUUID();
        return createRow(id, id, email, rawPassword, name, Role.ADMIN);
    }

    @Override
    @Transactional
    public UserView createUser(UUID ownerId, String email, String rawPassword, String name, String role) {
        requireActiveCaller(ownerId);
        return createRow(UUID.randomUUID(), ownerId, email, rawPassword, name, parseRole(role));
    }

    private UserView createRow(UUID id, UUID ownerId, String email, String rawPassword, String name, Role role) {
        AppUser user = new AppUser(id, ownerId, email, passwordEncoder.encode(rawPassword), name, role,
                UserStatus.ACTIVE);
        return save(user, email);
    }

    @Override
    @Transactional(readOnly = true)
    public UserView getUser(UUID ownerId, UUID id) {
        requireActiveCaller(ownerId);
        return AppUserMapper.toUserView(requireOwned(ownerId, id));
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserView> listUsers(UUID ownerId) {
        requireActiveCaller(ownerId);
        return userRepository.findAllByOwnerIdOrderByEmailAsc(ownerId).stream()
                .map(AppUserMapper::toUserView)
                .toList();
    }

    @Override
    @Transactional
    public UserView updateUser(UUID ownerId, UUID id, String email, String name, String role, String status) {
        requireActiveCaller(ownerId);
        AppUser user = requireOwned(ownerId, id);
        if (id.equals(ownerId)) {
            // An ADMIN row is self-owned (ownerId == id), so this is the one
            // case requireOwned's scope check cannot catch: an admin
            // suspending or downgrading their OWN row is
            // irreversible — no other admin shares their scope to undo it,
            // permanently locking every user in the scope out of
            // administration. Same self-destruction class as the
            // self-delete guard below.
            requireNotSelfLockout(role, status);
        }
        if (email != null && !email.isBlank()) {
            user.changeEmail(email);
        }
        if (name != null && !name.isBlank()) {
            user.changeName(name);
        }
        if (role != null && !role.isBlank()) {
            user.changeRole(parseRole(role));
        }
        if (status != null && !status.isBlank()) {
            user.changeStatus(parseStatus(status));
        }
        return save(user, email);
    }

    @Override
    @Transactional
    public void deleteUser(UUID ownerId, UUID id) {
        requireActiveCaller(ownerId);
        // An ADMIN row is self-owned (ownerId == id), so this is the one
        // case requireOwned's scope check cannot catch: deleting
        // it would orphan every MEMBER whose owner_id points at it, making
        // them permanently unreachable through the API.
        if (id.equals(ownerId)) {
            throw new ConflictException("Cannot delete your own account");
        }
        userRepository.delete(requireOwned(ownerId, id));
    }

    /**
     * {@code UsersController} authorizes purely off the JWT's {@code ADMIN}
     * role claim and never consulted the suspension gate ({@link
     * AccountStatusGuard}) that every other {@code application} class gates
     * through: a SUSPENDED admin holding a still-valid token kept full
     * {@code /api/v1/users} CRUD for the token's remaining TTL. {@code
     * ownerId} is always the CALLER's own id (an ADMIN's own row, per the
     * self-owned-ADMIN model — the same value {@code UsersController}
     * derives from the JWT subject for every operation), so loading it here
     * gates the caller, not just the target.
     */
    private void requireActiveCaller(UUID ownerId) {
        AppUser caller = userRepository.findById(ownerId)
                .orElseThrow(() -> new NotFoundException("User not found: " + ownerId));
        AccountStatusGuard.requireActive(caller);
    }

    /**
     * A self-edit that would suspend the caller's own status or downgrade
     * their own role away from ADMIN is rejected; a no-op self-update (same
     * status/role, or leaving either {@code null}) still succeeds, since
     * only the self-destructive transition is unsafe.
     */
    private void requireNotSelfLockout(String role, String status) {
        boolean suspendsSelf = status != null && !status.isBlank() && parseStatus(status) == UserStatus.SUSPENDED;
        boolean downgradesSelf = role != null && !role.isBlank() && parseRole(role) != Role.ADMIN;
        if (suspendsSelf || downgradesSelf) {
            throw new ConflictException("Cannot suspend or downgrade your own admin account");
        }
    }

    private UserView save(AppUser user, String email) {
        try {
            return AppUserMapper.toUserView(userRepository.saveAndFlush(user));
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("A user with email '" + email + "' already exists");
        }
    }

    private AppUser requireOwned(UUID ownerId, UUID id) {
        return userRepository.findByOwnerIdAndId(ownerId, id)
                .orElseThrow(() -> new NotFoundException("User not found: " + id));
    }

    /**
     * The web layer already constrains {@code role}/{@code status} to valid
     * enum names ({@code @Pattern}), so this only ever fires for a caller of
     * this {@code api} port that skips that validation (e.g. a future
     * programming error in the dev seeder) — defence in depth, not a
     * user-reachable path.
     */
    private static Role parseRole(String value) {
        try {
            return Role.valueOf(value);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException("Invalid role: " + value);
        }
    }

    private static UserStatus parseStatus(String value) {
        try {
            return UserStatus.valueOf(value);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException("Invalid status: " + value);
        }
    }
}
