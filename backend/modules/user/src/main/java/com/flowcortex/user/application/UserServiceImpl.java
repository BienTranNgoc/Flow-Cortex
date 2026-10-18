package com.flowcortex.user.application;

import com.flowcortex.shared.web.ConflictException;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.user.api.UserCreatedEvent;
import com.flowcortex.user.api.UserService;
import com.flowcortex.user.api.UserView;
import com.flowcortex.user.domain.AppUser;
import com.flowcortex.user.domain.Role;
import com.flowcortex.user.domain.UserStatus;
import com.flowcortex.user.infrastructure.AppUserRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementation of {@link UserService}.
 *
 * <p>Duplicate-email safety is not check-then-insert (races); every write flushes immediately
 * ({@code saveAndFlush}) so a {@code UNIQUE (email)} violation surfaces synchronously here as a
 * {@link DataIntegrityViolationException}, translated to {@link ConflictException} (409).
 */
@Service
class UserServiceImpl implements UserService {

    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokenService;
    private final ApplicationEventPublisher events;

    UserServiceImpl(AppUserRepository userRepository, PasswordEncoder passwordEncoder,
            RefreshTokenService refreshTokenService, ApplicationEventPublisher events) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.refreshTokenService = refreshTokenService;
        this.events = events;
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
        UserView created = save(user, email);
        events.publishEvent(new UserCreatedEvent(id));
        return created;
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
            // ADMIN row is self-owned (ownerId == id) — requireOwned's scope check can't
            // catch an admin locking out their own scope by suspending/downgrading self.
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
            UserStatus newStatus = parseStatus(status);
            user.changeStatus(newStatus);
            if (newStatus == UserStatus.SUSPENDED) {
                // Refresh tokens would otherwise keep minting access tokens for a suspended account.
                refreshTokenService.revokeAllFor(id);
            }
        }
        return save(user, email);
    }

    @Override
    @Transactional
    public void deleteUser(UUID ownerId, UUID id) {
        requireActiveCaller(ownerId);
        // ADMIN row is self-owned (ownerId == id) — deleting it would orphan every MEMBER whose
        // owner_id points at it, making them unreachable through the API.
        if (id.equals(ownerId)) {
            throw new ConflictException("Cannot delete your own account");
        }
        userRepository.delete(requireOwned(ownerId, id));
    }

    /**
     * {@code UsersController} authorizes purely off the JWT's {@code ADMIN} role claim and never
     * consulted {@link AccountStatusGuard} — a SUSPENDED admin with a still-valid token kept
     * full CRUD for its remaining TTL. {@code ownerId} here is always the caller's own id, so
     * loading it gates the caller, not just the target.
     */
    private void requireActiveCaller(UUID ownerId) {
        AppUser caller = userRepository.findById(ownerId)
                .orElseThrow(() -> new NotFoundException("User not found: " + ownerId));
        AccountStatusGuard.requireActive(caller);
    }

    /**
     * Rejects a self-edit that would suspend the caller or downgrade them from ADMIN; a no-op
     * self-update (same status/role, or {@code null}) still succeeds.
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
     * The web layer already constrains {@code role}/{@code status} via {@code @Pattern}; this
     * only fires for an {@code api} caller that skips validation — defence in depth, not a
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
