package com.flowcortex.app.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.AbstractPostgresBootTest;
import com.flowcortex.user.api.UserService;
import com.flowcortex.user.totp.ChallengeOrigin;
import com.flowcortex.user.totp.LoginChallenge;
import com.flowcortex.user.totp.LoginChallengeRepository;
import com.flowcortex.user.totp.RecoveryCodeRepository;
import com.flowcortex.user.totp.RecoveryCodeRepository.KeyedHash;
import com.flowcortex.user.totp.UserTotpRepository;
import com.flowcortex.user.totp.UserTotpRepository.FailureCount;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The TOTP tables decide every single-use outcome in one conditional statement: a step is accepted once,
 * the enable wins once, the tenth wrong code locks once, a recovery code or a challenge is spent once.
 * Every row seeded here rolls back.
 */
@Testcontainers
@SpringBootTest
@Transactional
class TotpSchemaIT extends AbstractPostgresBootTest {

    private static final Instant NOW = Instant.parse("2026-11-24T10:00:00Z");
    private static final int MAX_FAILED = 10;
    private static final int MAX_WRONG_CODES = 5;

    @Autowired
    private UserService userService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private UserTotpRepository totp;

    @Autowired
    private RecoveryCodeRepository recoveryCodes;

    @Autowired
    private LoginChallengeRepository challenges;

    @Test
    void aStepIsClaimedOnce() {
        UUID user = enrolled();

        assertThat(totp.claimStep(user, 100, NOW)).isEqualTo(1);
        assertThat(totp.claimStep(user, 100, NOW)).isZero();
        assertThat(totp.claimStep(user, 99, NOW)).isZero();
        assertThat(totp.claimStep(user, 101, NOW)).isEqualTo(1);
    }

    @Test
    void aStepIsNotClaimedWhileSetupIsPending() {
        UUID user = user();
        totp.savePending(user, "k1.iv.tag.ct");

        assertThat(totp.claimStep(user, 100, NOW)).isZero();
    }

    @Test
    void aStepIsNotClaimedWhileTheAccountIsLocked() {
        UUID user = enrolled();
        Instant lockedUntil = NOW.plus(Duration.ofMinutes(15));
        for (int i = 0; i < MAX_FAILED; i++) {
            totp.recordFailure(user, MAX_FAILED, lockedUntil, NOW);
        }

        assertThat(totp.claimStep(user, 100, NOW)).isZero();
        assertThat(totp.claimStep(user, 100, lockedUntil.minusSeconds(1))).isZero();
        assertThat(totp.claimStep(user, 100, lockedUntil)).isEqualTo(1);
    }

    @Test
    void enableWinsOnce() {
        UUID user = user();
        totp.savePending(user, "k1.iv.tag.ct");

        assertThat(totp.enable(user, 50)).isEqualTo(1);
        assertThat(totp.enable(user, 51)).isZero();
        assertThat(jdbc.queryForObject("SELECT last_used_step FROM user_totp WHERE user_id = ?", Long.class, user))
                .isEqualTo(50L);
    }

    @Test
    void theTenthFailureLocksAndResets() {
        UUID user = user();
        totp.savePending(user, "k1.iv.tag.ct");
        Instant lockedUntil = NOW.plus(Duration.ofMinutes(15));

        for (int count = 1; count < MAX_FAILED; count++) {
            Optional<FailureCount> failure = totp.recordFailure(user, MAX_FAILED, lockedUntil, NOW);
            assertThat(failure).contains(new FailureCount(count, null));
        }
        Optional<FailureCount> tenth = totp.recordFailure(user, MAX_FAILED, lockedUntil, NOW);

        assertThat(tenth).contains(new FailureCount(0, lockedUntil));
        assertThat(totp.recordFailure(user, MAX_FAILED, lockedUntil, NOW.plusSeconds(1))).isEmpty();
        assertThat(totp.recordFailure(user, MAX_FAILED, lockedUntil, lockedUntil.minusSeconds(1))).isEmpty();
        assertThat(totp.recordFailure(user, MAX_FAILED, lockedUntil.plusSeconds(60), lockedUntil))
                .contains(new FailureCount(1, null));
    }

    @Test
    void aCorrectCodeResetsTheCount() {
        UUID user = enrolled();
        totp.recordFailure(user, MAX_FAILED, NOW.plusSeconds(900), NOW);
        totp.recordFailure(user, MAX_FAILED, NOW.plusSeconds(900), NOW);

        totp.resetFailures(user);

        assertThat(jdbc.queryForObject("SELECT failed_attempts FROM user_totp WHERE user_id = ?", Integer.class, user))
                .isZero();
        assertThat(totp.recordFailure(user, MAX_FAILED, NOW.plusSeconds(900), NOW))
                .contains(new FailureCount(1, null));
    }

    @Test
    void aNewPendingSecretKeepsTheLock() {
        UUID user = user();
        totp.savePending(user, "k1.iv.tag.first");
        Instant lockedUntil = NOW.plus(Duration.ofMinutes(15));
        for (int i = 0; i < MAX_FAILED; i++) {
            totp.recordFailure(user, MAX_FAILED, lockedUntil, NOW);
        }
        Timestamp lockBefore = jdbc.queryForObject("SELECT locked_until FROM user_totp WHERE user_id = ?",
                Timestamp.class, user);
        Integer failedBefore = jdbc.queryForObject("SELECT failed_attempts FROM user_totp WHERE user_id = ?",
                Integer.class, user);

        assertThat(totp.savePending(user, "k1.iv.tag.second")).isEqualTo(1);

        assertThat(jdbc.queryForObject("SELECT secret_encrypted FROM user_totp WHERE user_id = ?", String.class, user))
                .isEqualTo("k1.iv.tag.second");
        assertThat(jdbc.queryForObject("SELECT locked_until FROM user_totp WHERE user_id = ?", Timestamp.class, user))
                .isEqualTo(lockBefore);
        assertThat(jdbc.queryForObject("SELECT failed_attempts FROM user_totp WHERE user_id = ?", Integer.class, user))
                .isEqualTo(failedBefore);
        assertThat(lockBefore).isNotNull();
    }

    @Test
    void aSecretIsNotReplacedOnceEnabled() {
        UUID user = enrolled();

        assertThat(totp.savePending(user, "k1.iv.tag.other")).isZero();

        assertThat(jdbc.queryForObject("SELECT secret_encrypted FROM user_totp WHERE user_id = ?", String.class, user))
                .isEqualTo("k1.iv.tag.ct");
    }

    @Test
    void aRecoveryCodeIsDeletedOnce() {
        UUID user = user();
        recoveryCodes.replaceAll(user, List.of(new KeyedHash("k1", hash('a')), new KeyedHash("k1", hash('b'))));

        assertThat(recoveryCodes.countFor(user)).isEqualTo(2);
        assertThat(recoveryCodes.consume(user, "k1", hash('a'), NOW)).isTrue();
        assertThat(recoveryCodes.consume(user, "k1", hash('a'), NOW)).isFalse();
        assertThat(recoveryCodes.consume(user, "k2", hash('b'), NOW)).isFalse();
        assertThat(recoveryCodes.countFor(user)).isEqualTo(1);
    }

    @Test
    void aRecoveryCodeIsNotSpentWhileTheAccountIsLocked() {
        UUID user = enrolled();
        recoveryCodes.replaceAll(user, List.of(new KeyedHash("k1", hash('a'))));
        Instant lockedUntil = NOW.plus(Duration.ofMinutes(15));
        for (int i = 0; i < MAX_FAILED; i++) {
            totp.recordFailure(user, MAX_FAILED, lockedUntil, NOW);
        }

        assertThat(recoveryCodes.consume(user, "k1", hash('a'), NOW)).isFalse();
        assertThat(recoveryCodes.countFor(user)).isEqualTo(1);
        assertThat(recoveryCodes.consume(user, "k1", hash('a'), lockedUntil)).isTrue();
    }

    @Test
    void aRecoveryCodeOfAnotherAccountIsNotSpent() {
        UUID owner = user();
        UUID other = user();
        recoveryCodes.replaceAll(owner, List.of(new KeyedHash("k1", hash('a'))));

        assertThat(recoveryCodes.consume(other, "k1", hash('a'), NOW)).isFalse();
        assertThat(recoveryCodes.countFor(owner)).isEqualTo(1);
    }

    @Test
    void replacingTheCodesDropsTheOldSet() {
        UUID user = user();
        recoveryCodes.replaceAll(user, List.of(new KeyedHash("k1", hash('a'))));

        recoveryCodes.replaceAll(user, List.of(new KeyedHash("k1", hash('b')), new KeyedHash("k1", hash('c'))));

        assertThat(recoveryCodes.consume(user, "k1", hash('a'), NOW)).isFalse();
        assertThat(recoveryCodes.countFor(user)).isEqualTo(2);
        assertThat(recoveryCodes.deleteAll(user)).isEqualTo(2);
        assertThat(recoveryCodes.countFor(user)).isZero();
    }

    @Test
    void aChallengeIsSpentOnce() {
        UUID user = user();
        UUID challenge = challenge(user, 's', NOW.plusSeconds(300));

        assertThat(challenges.spend(challenge, NOW, MAX_WRONG_CODES)).isTrue();
        assertThat(challenges.spend(challenge, NOW, MAX_WRONG_CODES)).isFalse();
        assertThat(challenges.findLive(hash('s'), NOW, MAX_WRONG_CODES)).isEmpty();
    }

    @Test
    void aChallengeIsNeverSpentAfterItExpires() {
        UUID user = user();
        UUID challenge = challenge(user, 'e', NOW.plusSeconds(300));

        assertThat(challenges.findLive(hash('e'), NOW.plusSeconds(300), MAX_WRONG_CODES)).isEmpty();
        assertThat(challenges.spend(challenge, NOW.plusSeconds(300), MAX_WRONG_CODES)).isFalse();
        assertThat(challenges.recordWrongCode(challenge, NOW.plusSeconds(300), MAX_WRONG_CODES)).isZero();
    }

    @Test
    void aChallengeDiesAtItsLastWrongCode() {
        UUID user = user();
        UUID challenge = challenge(user, 'w', NOW.plusSeconds(300));

        for (int remaining = MAX_WRONG_CODES - 1; remaining >= 0; remaining--) {
            assertThat(challenges.recordWrongCode(challenge, NOW, MAX_WRONG_CODES)).isEqualTo(remaining);
        }

        assertThat(challenges.recordWrongCode(challenge, NOW, MAX_WRONG_CODES)).isZero();
        assertThat(challenges.findLive(hash('w'), NOW, MAX_WRONG_CODES)).isEmpty();
        assertThat(challenges.spend(challenge, NOW, MAX_WRONG_CODES)).isFalse();
        assertThat(jdbc.queryForObject("SELECT failed_codes FROM login_challenge WHERE id = ?", Integer.class,
                challenge)).isEqualTo(MAX_WRONG_CODES);
    }

    @Test
    void aLiveChallengeCarriesWhatTheSignInNeeds() {
        UUID user = user();
        UUID challenge = challenge(user, 'l', NOW.plusSeconds(300));

        assertThat(challenges.findLive(hash('l'), NOW, MAX_WRONG_CODES)).contains(
                new LoginChallenge(challenge, user, true, ChallengeOrigin.LINK, NOW.plusSeconds(300), 0));
    }

    @Test
    void liveChallengesOfAUserAreDeleted() {
        UUID user = user();
        UUID other = user();
        challenge(user, 'a', NOW.plusSeconds(300));
        UUID spent = challenge(user, 'b', NOW.plusSeconds(300));
        challenges.spend(spent, NOW, MAX_WRONG_CODES);
        challenge(other, 'c', NOW.plusSeconds(300));

        assertThat(challenges.deleteLiveFor(user)).isEqualTo(1);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM login_challenge WHERE user_id = ?", Integer.class, user))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM login_challenge WHERE user_id = ?", Integer.class,
                other)).isEqualTo(1);
    }

    @Test
    void deadChallengesArePurged() {
        // deleteDead is table-wide and the HTTP ITs commit dead challenges of their own; the rollback undoes this.
        jdbc.update("DELETE FROM login_challenge");
        UUID user = user();
        UUID expired = challenge(user, 'a', NOW.minusSeconds(1));
        UUID spent = challenge(user, 'b', NOW.plusSeconds(300));
        challenges.spend(spent, NOW, MAX_WRONG_CODES);
        UUID live = challenge(user, 'c', NOW.plusSeconds(300));

        assertThat(challenges.deleteDead(NOW)).isEqualTo(2);

        assertThat(jdbc.queryForList("SELECT id FROM login_challenge WHERE user_id = ?", UUID.class, user))
                .containsExactly(live)
                .doesNotContain(expired, spent);
    }

    @Test
    void rowsGoWithTheirUser() {
        UUID user = user();
        totp.savePending(user, "k1.iv.tag.ct");
        recoveryCodes.replaceAll(user, List.of(new KeyedHash("k1", hash('a'))));
        challenge(user, 'g', NOW.plusSeconds(300));

        jdbc.update("DELETE FROM app_user WHERE id = ?", user);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_totp WHERE user_id = ?", Integer.class, user))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_recovery_code WHERE user_id = ?", Integer.class,
                user)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM login_challenge WHERE user_id = ?", Integer.class, user))
                .isZero();
    }

    private UUID user() {
        return userService.createInitialAdmin("totp-schema-" + UUID.randomUUID() + "@example.com", "password123",
                "Totp Schema").id();
    }

    private UUID enrolled() {
        UUID user = user();
        totp.savePending(user, "k1.iv.tag.ct");
        totp.enable(user, 1);
        return user;
    }

    /** A challenge whose token hash is {@code fill} repeated, so a test can look it up. */
    private UUID challenge(UUID user, char fill, Instant expiresAt) {
        return challenges.insert(user, hash(fill), true, ChallengeOrigin.LINK, expiresAt);
    }

    private static String hash(char fill) {
        return String.valueOf(fill).repeat(64);
    }
}
