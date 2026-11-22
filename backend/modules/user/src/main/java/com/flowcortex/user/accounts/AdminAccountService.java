package com.flowcortex.user.accounts;

import com.flowcortex.shared.web.ConflictException;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.shared.web.PageView;
import com.flowcortex.user.application.ConstraintViolations;
import com.flowcortex.user.application.RegistrationOwnerGuard;
import com.flowcortex.user.application.RoleCatalog;
import com.flowcortex.user.application.SuperAdminInvariant;
import com.flowcortex.user.domain.AppUser;
import com.flowcortex.user.domain.Role;
import com.flowcortex.user.infrastructure.AppUserRepository;
import com.flowcortex.user.infrastructure.RoleRow;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
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

    private final AppUserRepository users;
    private final SuperAdminInvariant superAdminInvariant;
    private final RegistrationOwnerGuard registrationOwnerGuard;
    private final RoleCatalog roleCatalog;

    AdminAccountService(AppUserRepository users, SuperAdminInvariant superAdminInvariant,
            RegistrationOwnerGuard registrationOwnerGuard, RoleCatalog roleCatalog) {
        this.users = users;
        this.superAdminInvariant = superAdminInvariant;
        this.registrationOwnerGuard = registrationOwnerGuard;
        this.roleCatalog = roleCatalog;
    }

    /**
     * @param role an optional role code to filter by; a code that is not an admin role matches nothing
     * @param q    an optional case-insensitive fragment of the email or name
     */
    @Transactional(readOnly = true)
    PageView<AdminAccountResponse> list(String role, String q, int page, int size) {
        boolean filtered = role != null && !role.isBlank();
        Set<Role> roles = roleCatalog.adminRoles().stream()
                .filter(row -> !filtered || row.code().equals(role))
                .map(row -> Role.of(row.code()))
                .collect(Collectors.toSet());
        if (roles.isEmpty()) {
            return new PageView<>(List.of(), page, size, 0);
        }
        Page<AppUser> found = users.searchByRoles(roles, likePattern(q), PageRequest.of(page, size));
        Map<String, String> roleNames = roleCatalog.names();
        return new PageView<>(found.getContent().stream().map(user -> AdminAccountResponse.from(user, roleNames))
                .toList(), page, size, found.getTotalElements());
    }

    @Transactional
    AdminAccountResponse changeRole(UUID callerId, UUID id, String role) {
        RoleRow newRoleRow = roleCatalog.require(role);
        Role newRole = Role.of(newRoleRow.code());
        if (id.equals(callerId)) {
            throw new ConflictException(new LocalizedMessage("user.cannotChangeOwnRole"));
        }
        AppUser target = requireAccount(id);
        if (target.getRole().isSuperAdmin() && !newRole.isSuperAdmin()) {
            registrationOwnerGuard.assertNotRegistrationOwner(target);
            superAdminInvariant.assertNotLastActive(target);
        }
        target.changeRole(newRole, newRoleRow.isAdminAccount());
        try {
            return AdminAccountResponse.from(users.saveAndFlush(target), roleCatalog.names());
        } catch (DataIntegrityViolationException e) {
            // A role deleted after the catalog lookup fails the foreign key at this flush, not at commit.
            if (ConstraintViolations.isRoleForeignKey(e)) {
                throw RoleCatalog.unknown(role);
            }
            throw e;
        }
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
        return AdminAccountResponse.from(target, roleCatalog.names());
    }

    @Transactional
    AdminAccountResponse activate(UUID id) {
        AppUser target = requireAdminAccount(id);
        if (target.isAdminDisabled()) {
            target.activateAdmin();
            users.saveAndFlush(target);
        }
        return AdminAccountResponse.from(target, roleCatalog.names());
    }

    /**
     * Row-locked, so role and deactivation writes on one account serialize and each one decides from
     * the state the previous one committed. A super admin target is locked through the invariant's
     * id-ordered lock first, so two requests on different super admins never wait on each other's rows.
     */
    private AppUser requireAccount(UUID id) {
        Role current = users.findRoleById(id)
                .orElseThrow(() -> new NotFoundException(new LocalizedMessage("user.notFound", id)));
        if (current.isSuperAdmin()) {
            users.lockActiveSuperAdmins();
        }
        return users.findByIdForUpdate(id)
                .orElseThrow(() -> new NotFoundException(new LocalizedMessage("user.notFound", id)));
    }

    private AppUser requireAdminAccount(UUID id) {
        AppUser target = requireAccount(id);
        if (!roleCatalog.find(target.getRole().code()).map(RoleRow::isAdminAccount).orElse(false)) {
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
