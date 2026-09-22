package com.flowcortex.user.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowcortex.shared.web.ConflictException;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.user.api.UserView;
import com.flowcortex.user.domain.AppUser;
import com.flowcortex.user.domain.Role;
import com.flowcortex.user.domain.UserStatus;
import com.flowcortex.user.infrastructure.AppUserRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * Task 4.3 (design.md D1/R-020/R-021/R-024/R-025), authored before {@link
 * UserServiceImpl} existed (red before green, per tasks.md's directive) —
 * pure unit coverage with a mocked repository; the R-024 concurrency proof
 * against a real database is {@code UserConcurrentCreateIT} (:boot:app).
 */
class UserServiceImplTest {

    private final AppUserRepository repository = mock(AppUserRepository.class);
    private final UserServiceImpl service =
            new UserServiceImpl(repository, new BCryptPasswordEncoder());

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
        // proves the format) — R-021.
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
    void createInitialAdminIsSelfOwned() {
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        UserView admin = service.createInitialAdmin("admin@example.com", "password123", "Admin");

        assertThat(admin.ownerId()).isEqualTo(admin.id());
        assertThat(admin.role()).isEqualTo("ADMIN");
    }

    /** G1 — {@code requireActiveCaller} loads this row for every {@code ownerId} argument above. */
    private static AppUser activeOwner(UUID ownerId) {
        return new AppUser(ownerId, ownerId, "owner@example.com", "$2a$hash", "Owner", Role.ADMIN,
                UserStatus.ACTIVE);
    }
}
