package com.flowcortex.user.application;

import com.flowcortex.shared.security.authz.Permission;
import com.flowcortex.shared.web.ConflictException;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.user.api.AdminPermissions;
import com.flowcortex.user.api.UserActivatedEvent;
import com.flowcortex.user.api.UserService;
import com.flowcortex.user.api.UserView;
import com.flowcortex.user.application.UserMutationPolicy.UserMutation;
import com.flowcortex.user.domain.AppUser;
import com.flowcortex.user.domain.Role;
import com.flowcortex.user.domain.UserStatus;
import com.flowcortex.user.emailconfirm.EmailConfirmationService;
import com.flowcortex.user.infrastructure.AppUserRepository;
import com.flowcortex.user.infrastructure.RoleRow;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private final EmailConfirmationService emailLinks;
    private final AdminPermissions adminPermissions;
    private final SuperAdminInvariant superAdminInvariant;
    private final RegistrationOwnerGuard registrationOwnerGuard;
    private final RoleCatalog roleCatalog;

    UserServiceImpl(AppUserRepository userRepository, PasswordEncoder passwordEncoder,
            RefreshTokenService refreshTokenService, ApplicationEventPublisher events,
            EmailConfirmationService emailLinks, AdminPermissions adminPermissions,
            SuperAdminInvariant superAdminInvariant, RegistrationOwnerGuard registrationOwnerGuard,
            RoleCatalog roleCatalog) {
        this.roleCatalog = roleCatalog;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.refreshTokenService = refreshTokenService;
        this.events = events;
        this.emailLinks = emailLinks;
        this.adminPermissions = adminPermissions;
        this.superAdminInvariant = superAdminInvariant;
        this.registrationOwnerGuard = registrationOwnerGuard;
    }

    @Override
    @Transactional
    public UserView createInitialAdmin(String email, String rawPassword, String name) {
        UUID id = UUID.randomUUID();
        return createRow(id, id, email, rawPassword, name, Role.ADMIN);
    }

    @Override
    @Transactional
    public UserView createUser(UUID callerId, String email, String rawPassword, String name, String role) {
        AppUser caller = requireActiveCaller(callerId);
        RoleRow newRole = roleCatalog.require(role);
        requireKeys(caller, UserMutationPolicy.requiredKeys(UserMutation.create(newRole)));
        return createRow(UUID.randomUUID(), scopeOf(caller), email, rawPassword, name, Role.of(newRole.code()));
    }

    private UserView createRow(UUID id, UUID ownerId, String email, String rawPassword, String name, Role role) {
        AppUser user = new AppUser(id, ownerId, email, passwordEncoder.encode(rawPassword), name, role,
                UserStatus.ACTIVE);
        UserView created = save(user, email, role.code());
        // An admin-created account is confirmed on creation, so it is usable at once.
        events.publishEvent(new UserActivatedEvent(id));
        return created;
    }

    @Override
    @Transactional(readOnly = true)
    public UserView getUser(UUID callerId, UUID id) {
        AppUser caller = requireActiveCaller(callerId);
        return AppUserMapper.toUserView(requireOwned(scopeOf(caller), id), roleCatalog.names());
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserView> listUsers(UUID callerId) {
        AppUser caller = requireActiveCaller(callerId);
        Map<String, String> roleNames = roleCatalog.names();
        return userRepository.findAllByOwnerIdOrderByEmailAsc(scopeOf(caller)).stream()
                .map(user -> AppUserMapper.toUserView(user, roleNames))
                .toList();
    }

    @Override
    @Transactional
    public UserView updateUser(UUID callerId, UUID id, String email, String name, String role, String status) {
        AppUser caller = requireActiveCaller(callerId);
        AppUser user = requireOwned(scopeOf(caller), id);
        RoleRow newRoleRow = role == null || role.isBlank() ? null : roleCatalog.require(role);
        Role newRole = newRoleRow == null ? null : Role.of(newRoleRow.code());
        UserStatus newStatus = status == null || status.isBlank() ? null : parseStatus(status);
        if (id.equals(callerId)) {
            // The caller's own row is in its scope, so requireOwned can't catch an admin
            // locking itself out by suspending or downgrading its own account.
            requireNotSelfLockout(user, newRole, newStatus);
        }
        requireKeys(caller, UserMutationPolicy.requiredKeys(UserMutation.update(
                roleCatalog.require(user.getRole().code()), newRoleRow, user.getStatus(), newStatus)));
        boolean leavesAdminRole = user.getRole().isSuperAdmin() && newRole != null && !newRole.isSuperAdmin();
        boolean suspends = newStatus == UserStatus.SUSPENDED;
        if (leavesAdminRole || suspends) {
            registrationOwnerGuard.assertNotRegistrationOwner(user);
            superAdminInvariant.assertNotLastActive(user);
        }
        if (email != null && !email.isBlank()) {
            if (!email.equalsIgnoreCase(user.getEmail())) {
                // A link mailed to the old address must not reset or confirm the account any more.
                emailLinks.retireOpenLinks(user.getId());
            }
            user.changeEmail(email);
        }
        if (name != null && !name.isBlank()) {
            user.changeName(name);
        }
        if (newRole != null) {
            user.changeRole(newRole, newRoleRow.isAdminAccount());
        }
        if (newStatus != null) {
            boolean reactivated = user.getStatus() != UserStatus.ACTIVE && newStatus == UserStatus.ACTIVE;
            user.changeStatus(newStatus);
            if (newStatus == UserStatus.SUSPENDED) {
                // Refresh tokens would otherwise keep minting access tokens for a suspended account,
                // and its emailed links would work again once it is reactivated.
                refreshTokenService.revokeAllFor(id);
                emailLinks.retireOpenLinks(id);
            }
            // An account confirmed while suspended was never activated; listeners tolerate a repeat.
            if (reactivated && user.isEmailConfirmed()) {
                events.publishEvent(new UserActivatedEvent(id));
            }
        }
        return save(user, email, role);
    }

    @Override
    @Transactional
    public void deleteUser(UUID callerId, UUID id) {
        AppUser caller = requireActiveCaller(callerId);
        // Deleting the caller's own row would orphan every account whose owner_id points at it,
        // making them unreachable through the API.
        if (id.equals(callerId)) {
            throw new ConflictException(new LocalizedMessage("user.cannotDeleteSelf"));
        }
        AppUser user = requireOwned(scopeOf(caller), id);
        requireKeys(caller, UserMutationPolicy.requiredKeys(
                UserMutation.delete(roleCatalog.require(user.getRole().code()))));
        registrationOwnerGuard.assertNotRegistrationOwner(user);
        superAdminInvariant.assertNotLastActive(user);
        userRepository.delete(user);
    }

    /**
     * The routes authorize by key, but a suspended caller with a still-valid token must not keep
     * working for the token's remaining TTL, so the caller's row is loaded and gated here too.
     */
    private AppUser requireActiveCaller(UUID callerId) {
        AppUser caller = userRepository.findById(callerId)
                .orElseThrow(() -> new NotFoundException(new LocalizedMessage("user.notFound", callerId)));
        AccountStatusGuard.requireActive(caller);
        return caller;
    }

    /** A super admin acts in the scope of its own id; staff act in the scope of the admin that owns them. */
    private static UUID scopeOf(AppUser caller) {
        return caller.getRole().isSuperAdmin() ? caller.getId() : caller.getOwnerId();
    }

    /**
     * The route posture checked the base key; this checks the keys the body adds. An active super
     * admin holds every key, so it costs no read; any other caller is checked against the keys of its role.
     */
    private void requireKeys(AppUser caller, Set<Permission> keys) {
        if (caller.getRole().isSuperAdmin() && !caller.isAdminDisabled()) {
            return;
        }
        for (Permission key : keys) {
            adminPermissions.require(caller.getId(), key);
        }
    }

    /**
     * Rejects a self-edit that would suspend the caller or change its role; a no-op self-update
     * (same status/role, or {@code null}) still succeeds.
     */
    private static void requireNotSelfLockout(AppUser self, Role newRole, UserStatus newStatus) {
        boolean suspendsSelf = newStatus == UserStatus.SUSPENDED;
        boolean changesOwnRole = newRole != null && newRole != self.getRole();
        if (suspendsSelf || changesOwnRole) {
            throw new ConflictException(new LocalizedMessage("user.cannotDemoteSelf"));
        }
    }

    /**
     * A role deleted after the catalog lookup fails {@code fk_app_user_role} at the flush: that is an
     * unknown role, not a taken email. Every other constraint keeps the email answer.
     */
    private UserView save(AppUser user, String email, String requestedRole) {
        try {
            return AppUserMapper.toUserView(userRepository.saveAndFlush(user), roleCatalog.names());
        } catch (DataIntegrityViolationException e) {
            if (ConstraintViolations.isRoleForeignKey(e)) {
                throw RoleCatalog.unknown(requestedRole);
            }
            throw new ConflictException(new LocalizedMessage("user.emailTaken", email));
        }
    }

    private AppUser requireOwned(UUID ownerId, UUID id) {
        return userRepository.findByOwnerIdAndId(ownerId, id)
                .orElseThrow(() -> new NotFoundException(new LocalizedMessage("user.notFound", id)));
    }

    private static UserStatus parseStatus(String value) {
        try {
            return UserStatus.valueOf(value);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException("Invalid status: " + value);
        }
    }
}
