package com.flowcortex.user.totp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowcortex.user.api.AuthSession;
import com.flowcortex.user.application.AuditEventWriter;
import com.flowcortex.user.application.AuthEventType;
import com.flowcortex.user.application.RefreshTokenService;
import com.flowcortex.user.application.RoleCatalog;
import com.flowcortex.user.domain.AppUser;
import com.flowcortex.user.domain.Role;
import com.flowcortex.user.domain.UserStatus;
import com.flowcortex.user.infrastructure.AppUserRepository;
import com.flowcortex.user.infrastructure.RoleRow;
import com.flowcortex.user.totp.RecoveryCodeRepository.KeyedHash;
import com.flowcortex.user.totp.TotpProblems.AlreadyEnabled;
import com.flowcortex.user.totp.TotpProblems.ChallengeInvalid;
import com.flowcortex.user.totp.TotpProblems.InvalidCode;
import com.flowcortex.user.totp.TotpProblems.Locked;
import com.flowcortex.user.totp.TotpProblems.NotEnabled;
import com.flowcortex.user.totp.TotpProblems.NotSetUp;
import com.flowcortex.user.totp.TotpProblems.RequiredForRole;
import com.flowcortex.user.totp.TotpProblems.Unavailable;
import com.flowcortex.user.totp.UserTotpRepository.FailureCount;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

class TotpServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-24T10:00:00Z");
    private static final long STEP = TotpCodes.stepOf(NOW);
    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    private static final String IP = "127.0.0.1";
    private static final String AGENT = "test-agent";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final FakeTotps totps = new FakeTotps();
    private final FakeRecoveryCodes recovery = new FakeRecoveryCodes();
    private final FakeChallenges challenges = new FakeChallenges();
    private final AppUserRepository users = mock(AppUserRepository.class);
    private final RoleCatalog roles = mock(RoleCatalog.class);
    private final RefreshTokenService sessions = mock(RefreshTokenService.class);
    private final AuditEventWriter audit = mock(AuditEventWriter.class);
    private final AtomicBoolean adminRequired = new AtomicBoolean(true);
    private final TotpProperties properties = new TotpProperties(Map.of("k1", key()), "k1", true, 10,
            Duration.ofMinutes(15), Duration.ofMinutes(5), 5, "Nodi", "");
    private final TotpSecretCipher cipher = new TotpSecretCipher(properties);
    private final AppUser member = user(Role.MEMBER);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);

    private final TotpService service = new TotpService(totps, recovery, challenges, cipher, properties,
            adminRequired::get, roles, users, sessions, audit, Clock.fixed(NOW, ZoneOffset.UTC), transactions);

    private static String key() {
        byte[] bytes = new byte[32];
        java.util.Arrays.fill(bytes, (byte) 7);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private AppUser user(Role role) {
        AppUser user = new AppUser(USER, USER, "person@example.com", "hash", "Person", role, UserStatus.ACTIVE);
        when(users.findById(USER)).thenReturn(Optional.of(user));
        when(roles.require(Role.ADMIN.code())).thenReturn(new RoleRow("ADMIN", "Admin", true, false, true));
        when(roles.require(Role.MEMBER.code())).thenReturn(new RoleRow("MEMBER", "Member", true, false, false));
        return user;
    }

    private record Enrolment(String secret, List<String> recoveryCodes) {
    }

    private Enrolment enrolled() {
        String secret = TotpCodes.newSecret(RANDOM);
        totps.rows.put(USER, new UserTotp(USER, cipher.encrypt(USER, secret), NOW.minusSeconds(3600), STEP - 5, 0,
                null));
        List<String> codes = RecoveryCodes.generate(RANDOM);
        recovery.replaceAll(USER, hashes(codes));
        return new Enrolment(secret, codes);
    }

    private List<KeyedHash> hashes(List<String> codes) {
        return codes.stream().map(code -> new KeyedHash("k1", cipher.recoveryHash("k1",
                RecoveryCodes.normalize(code)))).toList();
    }

    private void withFailures(int count, Instant lockedUntil) {
        UserTotp row = totps.rows.get(USER);
        totps.rows.put(USER, new UserTotp(USER, row.secretEncrypted(), row.enabledAt(), row.lastUsedStep(), count,
                lockedUntil));
    }

    private static String wrongCode(String secret) {
        Set<String> valid = new HashSet<>();
        for (long step = STEP - 1; step <= STEP + 1; step++) {
            valid.add(TotpCodes.codeAt(secret, step));
        }
        for (int candidate = 0; ; candidate++) {
            String code = "%06d".formatted(candidate);
            if (!valid.contains(code)) {
                return code;
            }
        }
    }

    @Test
    void lockIsCheckedBeforeTheCode() {
        Enrolment e = enrolled();
        withFailures(0, NOW.plusSeconds(600));

        assertThatThrownBy(() -> service.verifyForSignIn(USER, TotpCodes.codeAt(e.secret(), STEP), IP, AGENT))
                .isInstanceOfSatisfying(Locked.class,
                        locked -> assertThat(locked.lockedUntil()).isEqualTo(NOW.plusSeconds(600)));

        assertThat(totps.rows.get(USER).lastUsedStep()).isEqualTo(STEP - 5);
        assertThat(totps.rows.get(USER).failedAttempts()).isZero();
    }

    @Test
    void aLockSetWhileTheRequestWasInFlightIsSeenUnderTheRowLock() {
        Enrolment e = enrolled();
        totps.lockWhenRowIsLocked = NOW.plusSeconds(600);
        String typed = e.recoveryCodes().get(0);

        assertThatThrownBy(() -> service.verifyForSignIn(USER, TotpCodes.codeAt(e.secret(), STEP), IP, AGENT))
                .isInstanceOfSatisfying(Locked.class,
                        locked -> assertThat(locked.lockedUntil()).isEqualTo(NOW.plusSeconds(600)));
        assertThatThrownBy(() -> service.verifyForSignIn(USER, typed, IP, AGENT)).isInstanceOf(Locked.class);
        assertThatThrownBy(() -> service.disable(USER, TotpCodes.codeAt(e.secret(), STEP), IP, AGENT))
                .isInstanceOf(Locked.class);

        assertThat(totps.rows.get(USER).lastUsedStep()).isEqualTo(STEP - 5);
        assertThat(totps.rows.get(USER).failedAttempts()).isZero();
        assertThat(recovery.countFor(USER)).isEqualTo(10);
        assertThat(totps.rows).containsKey(USER);
    }

    @Test
    void aWrongCodeCommitsItsCountAndARefusalThatWroteNothingRollsBack() {
        Enrolment e = enrolled();

        assertThatThrownBy(() -> service.verifyForSignIn(USER, wrongCode(e.secret()), IP, AGENT))
                .isInstanceOf(InvalidCode.class);

        verify(transactions).commit(any());
        verify(transactions, never()).rollback(any());
        withFailures(0, NOW.plusSeconds(600));
        assertThatThrownBy(() -> service.verifyForSignIn(USER, TotpCodes.codeAt(e.secret(), STEP), IP, AGENT))
                .isInstanceOf(Locked.class);
        verify(transactions).rollback(any());
    }

    @Test
    void wrongCodeCountsAndTenthLocks() {
        Enrolment e = enrolled();
        withFailures(8, null);
        String wrong = wrongCode(e.secret());

        assertThatThrownBy(() -> service.verifyForSignIn(USER, wrong, IP, AGENT)).isInstanceOf(InvalidCode.class);
        assertThat(totps.rows.get(USER).failedAttempts()).isEqualTo(9);

        assertThatThrownBy(() -> service.verifyForSignIn(USER, wrong, IP, AGENT))
                .isInstanceOfSatisfying(Locked.class,
                        locked -> assertThat(locked.lockedUntil()).isEqualTo(NOW.plus(Duration.ofMinutes(15))));

        assertThat(totps.rows.get(USER).failedAttempts()).isZero();
        verify(audit, times(1)).write(eq(AuthEventType.TOTP_LOCKED), eq(USER), any(), any(), anyMap());
        verify(audit, times(2)).write(eq(AuthEventType.LOGIN_FAILED_TOTP), eq(USER), any(), any(), anyMap());
    }

    @Test
    void correctCodeResetsTheCount() {
        Enrolment e = enrolled();
        withFailures(4, null);

        TotpService.Method method = service.verifyForSignIn(USER, TotpCodes.codeAt(e.secret(), STEP + 1), IP, AGENT);

        assertThat(method).isEqualTo(TotpService.Method.TOTP);
        assertThat(totps.rows.get(USER).failedAttempts()).isZero();
        assertThat(totps.rows.get(USER).lastUsedStep()).isEqualTo(STEP + 1);
    }

    @Test
    void replayedStepIsRefusedAndCounts() {
        Enrolment e = enrolled();
        String code = TotpCodes.codeAt(e.secret(), STEP);

        service.verifyForSignIn(USER, code, IP, AGENT);

        assertThatThrownBy(() -> service.verifyForSignIn(USER, code, IP, AGENT)).isInstanceOf(InvalidCode.class);
        assertThat(totps.rows.get(USER).failedAttempts()).isEqualTo(1);
    }

    @Test
    void recoveryCodeIsSpentOnce() {
        Enrolment e = enrolled();
        String typed = e.recoveryCodes().get(0).toLowerCase().replace('-', ' ');

        assertThat(service.verifyForSignIn(USER, typed, IP, AGENT)).isEqualTo(TotpService.Method.RECOVERY_CODE);
        assertThatThrownBy(() -> service.verifyForSignIn(USER, typed, IP, AGENT)).isInstanceOf(InvalidCode.class);

        assertThat(recovery.countFor(USER)).isEqualTo(9);
        verify(audit, times(1)).write(eq(AuthEventType.TOTP_RECOVERY_CODE_USED), eq(USER), any(), any(), anyMap());
    }

    @Test
    void unreadableSecretIs503AndNotCounted() {
        Enrolment e = enrolled();
        totps.rows.put(USER, new UserTotp(USER, cipher.encrypt(OTHER, e.secret()), NOW.minusSeconds(60), null, 0,
                null));

        assertThatThrownBy(() -> service.verifyForSignIn(USER, TotpCodes.codeAt(e.secret(), STEP), IP, AGENT))
                .isInstanceOf(Unavailable.class);

        assertThat(totps.rows.get(USER).failedAttempts()).isZero();
    }

    @Test
    void signInWithoutTotpOnTheAccountIsAnInvalidChallenge() {
        assertThatThrownBy(() -> service.verifyForSignIn(USER, "123456", IP, AGENT))
                .isInstanceOf(ChallengeInvalid.class);
    }

    @Test
    void disableRefusedForAConsoleAccountBeforeTheCode() {
        Enrolment e = enrolled();
        user(Role.ADMIN);

        assertThatThrownBy(() -> service.disable(USER, wrongCode(e.secret()), IP, AGENT))
                .isInstanceOf(RequiredForRole.class);

        assertThat(totps.rows.get(USER).failedAttempts()).isZero();
        assertThat(totps.rows).containsKey(USER);
        assertThat(challenges.deletedFor).isEmpty();
    }

    @Test
    void disableAllowedForAConsoleAccountWhenNotRequired() {
        Enrolment e = enrolled();
        user(Role.ADMIN);
        adminRequired.set(false);

        service.disable(USER, TotpCodes.codeAt(e.secret(), STEP), IP, AGENT);

        assertThat(totps.rows).isEmpty();
        assertThat(recovery.countFor(USER)).isZero();
        verify(audit).write(eq(AuthEventType.TOTP_DISABLED), eq(USER), any(), any(), anyMap());
    }

    @Test
    void disableDeletesLiveChallenges() {
        Enrolment e = enrolled();

        service.disable(USER, TotpCodes.codeAt(e.secret(), STEP), IP, AGENT);

        assertThat(challenges.deletedFor).containsExactly(USER);
    }

    @Test
    void disableOfAnAccountWithoutTotpIsRefused() {
        assertThatThrownBy(() -> service.disable(USER, "123456", IP, AGENT)).isInstanceOf(NotEnabled.class);
    }

    @Test
    void regenerateRefusesARecoveryCode() {
        Enrolment e = enrolled();
        List<KeyedHash> before = new ArrayList<>(recovery.codes.get(USER));

        assertThatThrownBy(() -> service.regenerate(USER, e.recoveryCodes().get(0), IP, AGENT))
                .isInstanceOf(InvalidCode.class);

        assertThat(recovery.codes.get(USER)).containsExactlyInAnyOrderElementsOf(before);
        assertThat(totps.rows.get(USER).failedAttempts()).isEqualTo(1);
    }

    @Test
    void regenerateReplacesTheWholeSetWithATotpCode() {
        Enrolment e = enrolled();

        List<String> fresh = service.regenerate(USER, TotpCodes.codeAt(e.secret(), STEP), IP, AGENT);

        assertThat(fresh).hasSize(10).doesNotContainAnyElementsOf(e.recoveryCodes());
        assertThat(recovery.countFor(USER)).isEqualTo(10);
        assertThat(recovery.codes.get(USER)).containsExactlyInAnyOrderElementsOf(hashes(fresh));
        verify(audit).write(eq(AuthEventType.TOTP_RECOVERY_CODES_REGENERATED), eq(USER), any(), any(), anyMap());
    }

    @Test
    void setupAfterALockKeepsTheLock() {
        totps.rows.put(USER, new UserTotp(USER, cipher.encrypt(USER, "OLDSECRETOLDSECRETOLDSECRETOLDSE"), null,
                null, 3, NOW.plusSeconds(300)));

        TotpService.Setup setup = service.setup(USER);

        assertThat(setup.secret()).hasSize(32);
        assertThat(totps.rows.get(USER).lockedUntil()).isEqualTo(NOW.plusSeconds(300));
        assertThat(totps.rows.get(USER).failedAttempts()).isEqualTo(3);
        assertThat(service.status(USER).lockedUntil()).isEqualTo(NOW.plusSeconds(300));
    }

    @Test
    void setupDescribesTheSecretForAnAuthenticatorApp() {
        TotpService.Setup setup = service.setup(USER);

        assertThat(setup.otpauthUri()).startsWith("otpauth://totp/Nodi:person@example.com?secret=" + setup.secret())
                .contains("issuer=Nodi");
        assertThat(setup.qrDataUrl()).startsWith("data:image/svg+xml;base64,");
        assertThat(cipher.decrypt(USER, totps.rows.get(USER).secretEncrypted())).isEqualTo(setup.secret());
    }

    @Test
    void setupWhileEnabledIsRefusedAndKeepsTheSecret() {
        enrolled();
        String before = totps.rows.get(USER).secretEncrypted();

        assertThatThrownBy(() -> service.setup(USER)).isInstanceOf(AlreadyEnabled.class);

        assertThat(totps.rows.get(USER).secretEncrypted()).isEqualTo(before);
    }

    @Test
    void enableOpensAVerifiedSessionAndEndsTheOthers() {
        AuthSession session = new AuthSession("access", 900L, "refresh", NOW.plusSeconds(3600), true);
        when(sessions.rememberMeOf(USER, "cookie")).thenReturn(true);
        when(sessions.issueFor(member, IP, AGENT, true, NOW)).thenReturn(session);
        String secret = service.setup(USER).secret();

        TotpService.EnableResult result = service.enable(USER, TotpCodes.codeAt(secret, STEP), IP, AGENT, "cookie");

        assertThat(result.recoveryCodes()).hasSize(10);
        assertThat(result.session()).isSameAs(session);
        assertThat(totps.rows.get(USER).isEnabled()).isTrue();
        assertThat(totps.rows.get(USER).lastUsedStep()).isEqualTo(STEP);
        assertThat(recovery.countFor(USER)).isEqualTo(10);
        verify(sessions).revokeAllFor(USER);
        verify(audit).write(eq(AuthEventType.TOTP_ENABLED), eq(USER), any(), any(), anyMap());
    }

    @Test
    void enableWithoutSetupIsRefused() {
        assertThatThrownBy(() -> service.enable(USER, "123456", IP, AGENT, null)).isInstanceOf(NotSetUp.class);
    }

    @Test
    void enableWhileEnabledIsRefused() {
        Enrolment e = enrolled();

        assertThatThrownBy(() -> service.enable(USER, TotpCodes.codeAt(e.secret(), STEP), IP, AGENT, null))
                .isInstanceOf(AlreadyEnabled.class);
        verify(sessions, never()).revokeAllFor(any());
    }

    @Test
    void enableWithAWrongCodeCountsAndStaysPending() {
        String secret = service.setup(USER).secret();

        assertThatThrownBy(() -> service.enable(USER, wrongCode(secret), IP, AGENT, null))
                .isInstanceOf(InvalidCode.class);

        assertThat(totps.rows.get(USER).failedAttempts()).isEqualTo(1);
        assertThat(totps.rows.get(USER).isEnabled()).isFalse();
        verify(sessions, never()).revokeAllFor(any());
    }

    @Test
    void enableLosingTheRaceIsAlreadyEnabled() {
        String secret = service.setup(USER).secret();
        totps.enableBehindTheBack = true;

        assertThatThrownBy(() -> service.enable(USER, TotpCodes.codeAt(secret, STEP), IP, AGENT, null))
                .isInstanceOf(AlreadyEnabled.class);

        verify(sessions, never()).revokeAllFor(any());
    }

    @Test
    void statusReportsWhatTheAccountHas() {
        Enrolment e = enrolled();
        recovery.consume(USER, "k1", cipher.recoveryHash("k1", RecoveryCodes.normalize(e.recoveryCodes().get(0))),
                NOW);

        TotpService.Status status = service.status(USER);

        assertThat(status).isEqualTo(new TotpService.Status(true, false, false, 9, null));
    }

    @Test
    void requirementFollowsTheSwitchAndTheRole() {
        user(Role.ADMIN);

        assertThat(service.isRequired(Role.ADMIN.code())).isTrue();
        assertThat(service.isRequired(Role.MEMBER.code())).isFalse();
        adminRequired.set(false);
        assertThat(service.isRequired(Role.ADMIN.code())).isFalse();
    }

    private static final class FakeTotps extends UserTotpRepository {

        final Map<UUID, UserTotp> rows = new HashMap<>();
        boolean enableBehindTheBack;
        /** A lock a concurrent request writes after the caller's first read and before it takes the row lock. */
        Instant lockWhenRowIsLocked;

        FakeTotps() {
            super(null);
        }

        @Override
        Optional<UserTotp> findByUserId(UUID userId) {
            return Optional.ofNullable(rows.get(userId));
        }

        @Override
        Optional<UserTotp> findForUpdate(UUID userId) {
            UserTotp row = rows.get(userId);
            if (row != null && lockWhenRowIsLocked != null) {
                rows.put(userId, new UserTotp(userId, row.secretEncrypted(), row.enabledAt(), row.lastUsedStep(),
                        row.failedAttempts(), lockWhenRowIsLocked));
            }
            return findByUserId(userId);
        }

        @Override
        public boolean isEnabled(UUID userId) {
            return findByUserId(userId).filter(UserTotp::isEnabled).isPresent();
        }

        @Override
        public int savePending(UUID userId, String secretEncrypted) {
            UserTotp row = rows.get(userId);
            if (row != null && row.isEnabled()) {
                return 0;
            }
            rows.put(userId, new UserTotp(userId, secretEncrypted, null, null, row == null ? 0 : row.failedAttempts(),
                    row == null ? null : row.lockedUntil()));
            return 1;
        }

        @Override
        public int enable(UUID userId, long step) {
            UserTotp row = rows.get(userId);
            if (enableBehindTheBack || row == null || row.isEnabled()) {
                return 0;
            }
            rows.put(userId, new UserTotp(userId, row.secretEncrypted(), NOW, step, row.failedAttempts(),
                    row.lockedUntil()));
            return 1;
        }

        @Override
        public int claimStep(UUID userId, long step, Instant now) {
            UserTotp row = rows.get(userId);
            if (row == null || !row.isEnabled() || row.isLockedAt(now)
                    || (row.lastUsedStep() != null && row.lastUsedStep() >= step)) {
                return 0;
            }
            rows.put(userId, new UserTotp(userId, row.secretEncrypted(), row.enabledAt(), step, row.failedAttempts(),
                    row.lockedUntil()));
            return 1;
        }

        @Override
        public Optional<FailureCount> recordFailure(UUID userId, int max, Instant lockedUntil, Instant now) {
            UserTotp row = rows.get(userId);
            if (row == null || row.isLockedAt(now)) {
                return Optional.empty();
            }
            int count = row.failedAttempts() + 1;
            boolean locks = count >= max;
            rows.put(userId, new UserTotp(userId, row.secretEncrypted(), row.enabledAt(), row.lastUsedStep(),
                    locks ? 0 : count, locks ? lockedUntil : null));
            return Optional.of(new FailureCount(locks ? 0 : count, locks ? lockedUntil : null));
        }

        @Override
        public void resetFailures(UUID userId) {
            UserTotp row = rows.get(userId);
            if (row != null) {
                rows.put(userId, new UserTotp(userId, row.secretEncrypted(), row.enabledAt(), row.lastUsedStep(), 0,
                        row.lockedUntil()));
            }
        }

        @Override
        public int delete(UUID userId) {
            return rows.remove(userId) == null ? 0 : 1;
        }
    }

    private static final class FakeRecoveryCodes extends RecoveryCodeRepository {

        final Map<UUID, Set<KeyedHash>> codes = new HashMap<>();

        FakeRecoveryCodes() {
            super(null);
        }

        @Override
        public void replaceAll(UUID userId, List<KeyedHash> fresh) {
            codes.put(userId, new HashSet<>(fresh));
        }

        @Override
        public boolean consume(UUID userId, String keyId, String hash, Instant now) {
            return codes.getOrDefault(userId, Set.of()).remove(new KeyedHash(keyId, hash));
        }

        @Override
        public int countFor(UUID userId) {
            return codes.getOrDefault(userId, Set.of()).size();
        }

        @Override
        public int deleteAll(UUID userId) {
            Set<KeyedHash> removed = codes.remove(userId);
            return removed == null ? 0 : removed.size();
        }
    }

    private static final class FakeChallenges extends LoginChallengeRepository {

        final List<UUID> deletedFor = new ArrayList<>();

        FakeChallenges() {
            super(null);
        }

        @Override
        public int deleteLiveFor(UUID userId) {
            deletedFor.add(userId);
            return 0;
        }
    }
}
