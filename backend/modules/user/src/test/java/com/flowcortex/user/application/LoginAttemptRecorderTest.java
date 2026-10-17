package com.flowcortex.user.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.flowcortex.user.domain.AppUser;
import com.flowcortex.user.domain.Role;
import com.flowcortex.user.domain.UserStatus;
import com.flowcortex.user.infrastructure.AppUserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class LoginAttemptRecorderTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-10-14T00:00:00Z"), ZoneOffset.UTC);

    private final AppUserRepository repository = mock(AppUserRepository.class);
    private final LoginAttemptRecorder recorder = new LoginAttemptRecorder(repository, FIXED);

    @Test
    void fiveFailuresLockTheAccountForFifteenMinutes() {
        AppUser user = user();
        when(repository.findByIdForUpdate(user.getId())).thenReturn(Optional.of(user));

        for (int attempt = 1; attempt <= 5; attempt++) {
            recorder.recordFailure(user.getId());
        }

        assertThat(user.getFailedLoginAttempts()).isEqualTo((short) 5);
        assertThat(user.getLockedUntil()).isEqualTo(Instant.now(FIXED).plusSeconds(15 * 60));
    }

    @Test
    void successClearsTheCounterAndTheLock() {
        AppUser user = user();
        when(repository.findByIdForUpdate(user.getId())).thenReturn(Optional.of(user));
        recorder.recordFailure(user.getId());

        recorder.recordSuccess(user.getId());

        assertThat(user.getFailedLoginAttempts()).isZero();
        assertThat(user.getLockedUntil()).isNull();
    }

    @Test
    void everyWriteCommitsInItsOwnTransactionSoTheFailedLoginCannotRollItBack() throws NoSuchMethodException {
        for (String method : new String[] {"recordFailure", "recordSuccess"}) {
            Transactional tx = LoginAttemptRecorder.class.getDeclaredMethod(method, UUID.class)
                    .getAnnotation(Transactional.class);
            assertThat(tx).as(method).isNotNull();
            assertThat(tx.propagation()).as(method).isEqualTo(Propagation.REQUIRES_NEW);
        }
    }

    private static AppUser user() {
        UUID id = UUID.randomUUID();
        return new AppUser(id, id, "person@example.com", "hash", "Person", Role.MEMBER, UserStatus.ACTIVE);
    }
}
