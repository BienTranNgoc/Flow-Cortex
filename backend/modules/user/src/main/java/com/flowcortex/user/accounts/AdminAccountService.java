package com.flowcortex.user.accounts;

import com.flowcortex.shared.web.ConflictException;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.shared.web.PageView;
import com.flowcortex.user.application.RegistrationOwnerGuard;
import com.flowcortex.user.application.SuperAdminInvariant;
import com.flowcortex.user.domain.AppUser;
import com.flowcortex.user.domain.Role;
import com.flowcortex.user.infrastructure.AppUserRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin accounts: every account whose role is staff or {@code ADMIN}, across all owner scopes. The
 * caller's key is checked by the route; the rules that depend on the target live here.
 */
@Service
class AdminAccountService {

    private static final Set<Role> ADMIN_ROLES = EnumSet.copyOf(
            Arrays.stream(Role.values()).filter(r -> r.isSuperAdmin() || r.isStaff()).toList());

    private final AppUserRepository users;
    private final SuperAdminInvariant superAdminInvariant;
    private final RegistrationOwnerGuard registrationOwnerGuard;

    AdminAccountService(AppUserRepository users, SuperAdminInvariant superAdminInvariant,
            RegistrationOwnerGuard registrationOwnerGuard) {
        this.users = users;
        this.superAdminInvariant = superAdminInvariant;
        this.registrationOwnerGuard = registrationOwnerGuard;
    }

    /**
     * @param role an optional role code to filter by; a role that is not an admin role matches nothing
     * @param q    an optional case-insensitive fragment of the email or name
     */
    @Transactional(readOnly = true)
    PageView<AdminAccountResponse> list(String role, String q, int page, int size) {
        Set<Role> roles = ADMIN_ROLES;
        if (role != null && !role.isBlank()) {
            Role filter = Role.valueOf(role);
            roles = ADMIN_ROLES.contains(filter) ? EnumSet.of(filter) : EnumSet.noneOf(Role.class);
        }
        if (roles.isEmpty()) {
            return new PageView<>(List.of(), page, size, 0);
        }
        Page<AppUser> found = users.searchByRoles(roles, likePattern(q), PageRequest.of(page, size));
        return new PageView<>(found.getContent().stream().map(AdminAccountResponse::from).toList(), page, size,
                found.getTotalElements());
    }

    @Transactional
    AdminAccountResponse changeRole(UUID callerId, UUID id, String role) {
        Role newRole = Role.valueOf(role);
        if (id.equals(callerId)) {
            throw new ConflictException(new LocalizedMessage("user.cannotChangeOwnRole"));
        }
        AppUser target = requireAccount(id);
        if (target.getRole().isSuperAdmin() && !newRole.isSuperAdmin()) {
            registrationOwnerGuard.assertNotRegistrationOwner(target);
            superAdminInvariant.assertNotLastActive(target);
        }
        target.changeRole(newRole);
        return AdminAccountResponse.from(users.saveAndFlush(target));
    }

    /** Idempotent: a second deactivation keeps the first time and the first actor. */
    @Transactional
    AdminAccountResponse deactivate(UUID callerId, UUID id) {
        if (id.equals(callerId)) {
            throw new ConflictException(new LocalizedMessage("user.cannotDeactivateSelf"));
        }
        AppUser target = requireAdminAccount(id);
        if (!target.isAdminDisabled()) {
            superAdminInvariant.assertNotLastActive(target);
            // Postgres keeps microseconds; the response must equal what a later read returns.
            target.deactivateAdmin(callerId, Instant.now().truncatedTo(ChronoUnit.MICROS));
            users.saveAndFlush(target);
        }
        return AdminAccountResponse.from(target);
    }

    @Transactional
    AdminAccountResponse activate(UUID id) {
        AppUser target = requireAdminAccount(id);
        if (target.isAdminDisabled()) {
            target.activateAdmin();
            users.saveAndFlush(target);
        }
        return AdminAccountResponse.from(target);
    }

    private AppUser requireAccount(UUID id) {
        return users.findById(id)
                .orElseThrow(() -> new NotFoundException(new LocalizedMessage("user.notFound", id)));
    }

    private AppUser requireAdminAccount(UUID id) {
        AppUser target = requireAccount(id);
        if (!ADMIN_ROLES.contains(target.getRole())) {
            throw new ConflictException(new LocalizedMessage("user.notAdminAccount"));
        }
        return target;
    }

    /** A lower-case {@code %fragment%} pattern for {@code LIKE ... ESCAPE '!'}, with the user's wildcards made literal. */
    private static String likePattern(String q) {
        if (q == null || q.isBlank()) {
            return "%";
        }
        String escaped = q.trim().toLowerCase(Locale.ROOT)
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_");
        return "%" + escaped + "%";
    }
}
