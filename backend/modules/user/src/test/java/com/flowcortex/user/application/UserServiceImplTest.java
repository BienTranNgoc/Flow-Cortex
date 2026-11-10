package com.flowcortex.user.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.flowcortex.shared.security.authz.Permission;
import com.flowcortex.shared.web.ConflictException;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.user.api.AdminPermissions;
import com.flowcortex.user.api.UserActivatedEvent;
import com.flowcortex.user.api.UserView;
import com.flowcortex.user.domain.AppUser;
import com.flowcortex.user.domain.Role;
import com.flowcortex.user.domain.UserStatus;
import com.flowcortex.user.emailconfirm.EmailConfirmationService;
import com.flowcortex.user.infrastructure.AppUserRepository;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * Authored before {@link UserServiceImpl} existed (red before green) — pure
 * unit coverage with a mocked repository; the concurrency proof against a
 * real database is {@code UserConcurrentCreateIT} (:boot:app).
 */
class UserServiceImplTest {

    private final AppUserRepository repository = mock(AppUserRepository.class);
    private final AccountCredentials accountCredentials = mock(AccountCredentials.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final EmailConfirmationService emailLinks = mock(EmailConfirmationService.class);
    private final AdminPermissions adminPermissions = mock(AdminPermissions.class);
    private final SuperAdminInvariant superAdminInvariant = mock(SuperAdminInvariant.class);
    private final RegistrationOwnerGuard registrationOwnerGuard = mock(RegistrationOwnerGuard.class);
    private final UserServiceImpl service =
            new UserServiceImpl(repository, new BCryptPasswordEncoder(), accountCredentials, events, emailLinks,
                    adminPermissions, superAdminInvariant, registrationOwnerGuard);

    @Test
    void createUserDefaultsToActiveAndHashesThePasswordWithBcrypt() {
        UUID ownerId = UUID.randomUUID();
        when(repository.findById(ownerId)).thenReturn(Optional.of(activeOwner(ownerId)));
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        UserView created = service.createUser(ownerId, "new@example.com", "correct horse battery staple",
                "New Person", "MEMBER");

        assertThat(created.email()).isEqualTo("new@example.com");
        assertThat(created.status()).isEqualTo("ACTIVE");
        assertThat(created.role()).isEqualTo("MEMBER");
        assertThat(created.ownerId()).isEqualTo(ownerId);

        ArgumentCaptor<AppUser> captor = ArgumentCaptor.forClass(AppUser.class);
        verify(repository).saveAndFlush(captor.capture());
        AppUser saved = captor.getValue();
        // Never the raw password, and a real BCrypt hash (the encoder itself
        // proves the format).
        assertThat(saved.getPasswordHash()).isNotEqualTo("correct horse battery staple");
        assertThat(saved.getPasswordHash()).startsWith("$2");
        // UserView (what a controller can ever return) simply has no
        // password field — the strongest guarantee that it never leaks.
    }

    @Test
    void createUserDuplicateEmailIsTranslatedToConflictNotADatabaseException() {
        UUID ownerId = UUID.randomUUID();
        when(repository.findById(ownerId)).thenReturn(Optional.of(activeOwner(ownerId)));
        when(repository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uq_app_user_email"));

        assertThatThrownBy(() -> service.createUser(ownerId, "dup@example.com", "password123",
                "Someone", "MEMBER"))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void getUserOutsideTheCallersOwnerScopeIsNotFoundNot403() {
        UUID ownerId = UUID.randomUUID();
        UUID otherScopeId = UUID.randomUUID();
        when(repository.findById(ownerId)).thenReturn(Optional.of(activeOwner(ownerId)));
        when(repository.findByOwnerIdAndId(ownerId, otherScopeId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getUser(ownerId, otherScopeId))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void updateUserPartialUpdateOnlyChangesTheSuppliedFields() {
        UUID ownerId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        AppUser existing = new AppUser(id, ownerId, "person@example.com", "$2a$hash", "Original Name",
                Role.MEMBER, UserStatus.ACTIVE);
        when(repository.findById(ownerId)).thenReturn(Optional.of(activeOwner(ownerId)));
        when(repository.findByOwnerIdAndId(ownerId, id)).thenReturn(Optional.of(existing));
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        UserView updated = service.updateUser(ownerId, id, null, null, null, "SUSPENDED");

        assertThat(updated.status()).isEqualTo("SUSPENDED");
        assertThat(updated.email()).isEqualTo("person@example.com");
        assertThat(updated.name()).isEqualTo("Original Name");
        assertThat(updated.role()).isEqualTo("MEMBER");
    }

    @Test
    void suspendingAUserRevokesEveryRefreshSessionOfThatUser() {
        UUID ownerId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        AppUser existing = new AppUser(id, ownerId, "person@example.com", "$2a$hash", "Person", Role.MEMBER,
                UserStatus.ACTIVE);
        when(repository.findById(ownerId)).thenReturn(Optional.of(activeOwner(ownerId)));
        when(repository.findByOwnerIdAndId(ownerId, id)).thenReturn(Optional.of(existing));
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.updateUser(ownerId, id, null, null, null, "SUSPENDED");

        verify(accountCredentials).revokeAllFor(id);
    }

    @Test
    void anUpdateThatDoesNotSuspendKeepsTheRefreshSessions() {
        UUID ownerId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        AppUser existing = new AppUser(id, ownerId, "person@example.com", "$2a$hash", "Person", Role.MEMBER,
                UserStatus.ACTIVE);
        when(repository.findById(ownerId)).thenReturn(Optional.of(activeOwner(ownerId)));
        when(repository.findByOwnerIdAndId(ownerId, id)).thenReturn(Optional.of(existing));
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.updateUser(ownerId, id, null, "New Name", null, "ACTIVE");

        verify(accountCredentials, never()).revokeAllFor(any());
    }

    @Test
    void anEmailChangeRetiresTheAccountsOpenEmailedLinks() {
        UUID ownerId = UUID.randomUUID();
        AppUser member = ownedMember(ownerId, UserStatus.ACTIVE);

        service.updateUser(ownerId, member.getId(), "new@example.com", null, null, null);

        verify(emailLinks).retireOpenLinks(member.getId());
    }

    @Test
    void theSameEmailInAnotherCaseKeepsTheOpenEmailedLinks() {
        UUID ownerId = UUID.randomUUID();
        AppUser member = ownedMember(ownerId, UserStatus.ACTIVE);

        service.updateUser(ownerId, member.getId(), member.getEmail().toUpperCase(Locale.ROOT), null, null, null);

        verify(emailLinks, never()).retireOpenLinks(any());
    }

    @Test
    void reactivatingAConfirmedAccountPublishesItsActivation() {
        UUID ownerId = UUID.randomUUID();
        AppUser suspended = ownedMember(ownerId, UserStatus.SUSPENDED);

        service.updateUser(ownerId, suspended.getId(), null, null, null, "ACTIVE");

        verify(events).publishEvent(new UserActivatedEvent(suspended.getId()));
    }

    @Test
    void reactivatingAnUnconfirmedAccountOrKeepingAStatusPublishesNothing() {
        UUID ownerId = UUID.randomUUID();
        AppUser unconfirmed = AppUser.selfRegistered(UUID.randomUUID(), ownerId, "person@example.com", "$2a$hash",
                "A", "B", null, null);
        unconfirmed.changeStatus(UserStatus.SUSPENDED);
        when(repository.findByOwnerIdAndId(ownerId, unconfirmed.getId())).thenReturn(Optional.of(unconfirmed));
        AppUser active = ownedMember(ownerId, UserStatus.ACTIVE);
        AppUser suspended = ownedMember(ownerId, UserStatus.SUSPENDED);

        service.updateUser(ownerId, unconfirmed.getId(), null, null, null, "ACTIVE");
        service.updateUser(ownerId, active.getId(), null, null, null, "ACTIVE");
        service.updateUser(ownerId, suspended.getId(), null, null, null, "SUSPENDED");

        verifyNoInteractions(events);
    }

    private AppUser ownedMember(UUID ownerId, UserStatus status) {
        AppUser member = new AppUser(UUID.randomUUID(), ownerId, "person@example.com", "$2a$hash", "Person",
                Role.MEMBER, status);
        when(repository.findById(ownerId)).thenReturn(Optional.of(activeOwner(ownerId)));
        when(repository.findByOwnerIdAndId(ownerId, member.getId())).thenReturn(Optional.of(member));
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        return member;
    }

    @Test
    void createInitialAdminIsSelfOwned() {
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        UserView admin = service.createInitialAdmin("admin@example.com", "password123", "Admin");

        assertThat(admin.ownerId()).isEqualTo(admin.id());
        assertThat(admin.role()).isEqualTo("ADMIN");
    }

    @Test
    void aDelegatedAdminKeepsItsOwnScope() {
        UUID creatorId = UUID.randomUUID();
        AppUser delegated = new AppUser(UUID.randomUUID(), creatorId, "binh@example.com", "$2a$hash", "Binh",
                Role.ADMIN, UserStatus.ACTIVE);
        AppUser dung = new AppUser(UUID.randomUUID(), delegated.getId(), "dung@example.com", "$2a$hash", "Dung",
                Role.MEMBER, UserStatus.ACTIVE);
        when(repository.findById(delegated.getId())).thenReturn(Optional.of(delegated));
        when(repository.findAllByOwnerIdOrderByEmailAsc(delegated.getId())).thenReturn(List.of(dung));

        assertThat(service.listUsers(delegated.getId())).extracting(UserView::email)
                .containsExactly("dung@example.com");
        verify(repository, never()).findAllByOwnerIdOrderByEmailAsc(creatorId);
    }

    @Test
    void aStaffCallerActsInTheScopeOfTheAdminThatOwnsIt() {
        UUID adminId = UUID.randomUUID();
        AppUser staff = new AppUser(UUID.randomUUID(), adminId, "linh@example.com", "$2a$hash", "Linh",
                Role.CUSTOMER_SUPPORT, UserStatus.ACTIVE);
        AppUser member = new AppUser(UUID.randomUUID(), adminId, "ba@example.com", "$2a$hash", "Ba",
                Role.MEMBER, UserStatus.ACTIVE);
        when(repository.findById(staff.getId())).thenReturn(Optional.of(staff));
        when(repository.findAllByOwnerIdOrderByEmailAsc(adminId)).thenReturn(List.of(member, staff));
        when(repository.findByOwnerIdAndId(adminId, member.getId())).thenReturn(Optional.of(member));

        assertThat(service.listUsers(staff.getId())).hasSize(2);
        assertThat(service.getUser(staff.getId(), member.getId()).email()).isEqualTo("ba@example.com");
    }

    @Test
    void aMissingKeyRefusesTheChangeAndWritesNothing() {
        UUID adminId = UUID.randomUUID();
        AppUser manager = new AppUser(UUID.randomUUID(), adminId, "minh@example.com", "$2a$hash", "Minh",
                Role.OPERATION_MANAGER, UserStatus.ACTIVE);
        AppUser member = new AppUser(UUID.randomUUID(), adminId, "ba@example.com", "$2a$hash", "Ba",
                Role.MEMBER, UserStatus.ACTIVE);
        when(repository.findById(manager.getId())).thenReturn(Optional.of(manager));
        when(repository.findByOwnerIdAndId(adminId, member.getId())).thenReturn(Optional.of(member));
        doThrow(new AccessDeniedException("Missing permission"))
                .when(adminPermissions).require(manager.getId(), Permission.SYSTEM_ADMIN_ACCOUNT_MANAGE);

        assertThatThrownBy(() -> service.updateUser(manager.getId(), member.getId(), null, null,
                "CUSTOMER_SUPPORT", null)).isInstanceOf(AccessDeniedException.class);

        assertThat(member.getRole()).isEqualTo(Role.MEMBER);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void theSuperAdminInvariantRunsBeforeTheTargetIsChanged() {
        UUID adminId = UUID.randomUUID();
        AppUser caller = activeOwner(adminId);
        AppUser other = new AppUser(UUID.randomUUID(), adminId, "binh@example.com", "$2a$hash", "Binh",
                Role.ADMIN, UserStatus.ACTIVE);
        when(repository.findById(adminId)).thenReturn(Optional.of(caller));
        when(repository.findByOwnerIdAndId(adminId, other.getId())).thenReturn(Optional.of(other));
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        doAnswer(invocation -> {
            assertThat(other.getRole()).isEqualTo(Role.ADMIN);
            assertThat(other.getStatus()).isEqualTo(UserStatus.ACTIVE);
            return null;
        }).when(superAdminInvariant).assertNotLastActive(other);

        service.updateUser(adminId, other.getId(), null, null, "MEMBER", "SUSPENDED");

        verify(superAdminInvariant).assertNotLastActive(other);
        assertThat(other.getRole()).isEqualTo(Role.MEMBER);
    }

    @Test
    void aRefusedRegistrationOwnerChangeWritesNothing() {
        UUID adminId = UUID.randomUUID();
        AppUser caller = activeOwner(adminId);
        AppUser owner = new AppUser(UUID.randomUUID(), adminId, "an@example.com", "$2a$hash", "An",
                Role.ADMIN, UserStatus.ACTIVE);
        when(repository.findById(adminId)).thenReturn(Optional.of(caller));
        when(repository.findByOwnerIdAndId(adminId, owner.getId())).thenReturn(Optional.of(owner));
        doThrow(new ConflictException(new LocalizedMessage("user.registrationOwner")))
                .when(registrationOwnerGuard).assertNotRegistrationOwner(owner);

        assertThatThrownBy(() -> service.updateUser(adminId, owner.getId(), null, null, "OPERATION_MANAGER", null))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> service.deleteUser(adminId, owner.getId())).isInstanceOf(ConflictException.class);

        assertThat(owner.getRole()).isEqualTo(Role.ADMIN);
        verify(repository, never()).saveAndFlush(any());
        verify(repository, never()).delete(any());
        verifyNoInteractions(superAdminInvariant);
    }

    @Test
    void deletingChecksTheInvariantBeforeTheRowIsDeleted() {
        UUID adminId = UUID.randomUUID();
        AppUser caller = activeOwner(adminId);
        AppUser other = new AppUser(UUID.randomUUID(), adminId, "binh@example.com", "$2a$hash", "Binh",
                Role.ADMIN, UserStatus.ACTIVE);
        when(repository.findById(adminId)).thenReturn(Optional.of(caller));
        when(repository.findByOwnerIdAndId(adminId, other.getId())).thenReturn(Optional.of(other));

        service.deleteUser(adminId, other.getId());

        var order = inOrder(superAdminInvariant, repository);
        order.verify(superAdminInvariant).assertNotLastActive(other);
        order.verify(repository).delete(other);
    }

    /** {@code requireActiveCaller} loads this row for every {@code ownerId} argument above. */
    private static AppUser activeOwner(UUID ownerId) {
        return new AppUser(ownerId, ownerId, "owner@example.com", "$2a$hash", "Owner", Role.ADMIN,
                UserStatus.ACTIVE);
    }
}
