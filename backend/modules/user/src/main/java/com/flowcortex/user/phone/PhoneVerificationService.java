package com.flowcortex.user.phone;

import com.flowcortex.shared.web.ConflictException;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.RequestLocale;
import com.flowcortex.shared.web.UnprocessableEntityException;
import com.flowcortex.user.phone.OtpSendPolicy.Decision;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Proves a member owns a phone number with a 6-digit code. Sends to one number are serialised by a
 * transaction-scoped advisory lock on that number, so the caps and the cooldown read every earlier
 * send. Log lines carry the last three digits of a number and an {@code outcome=}, never the code.
 */
@Service
class PhoneVerificationService {

    static final Duration CODE_TTL = Duration.ofMinutes(5);
    static final LocalizedMessage NUMBER_TAKEN = new LocalizedMessage("phone.numberTaken");

    private static final Logger LOG = LoggerFactory.getLogger(PhoneVerificationService.class);
    private static final Duration COUNTED_SENDS = Duration.ofHours(24);
    private static final String VERIFIED_NUMBER_UNIQUE_INDEX = "uq_user_phone_verified_number";

    private final PhoneChallengeRepository challenges;
    private final UserPhoneRepository phones;
    private final SmsSender smsSender;
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final MessageSource messages;
    private final RequestLocale requestLocale;
    private final SecureRandom random = new SecureRandom();

    PhoneVerificationService(PhoneChallengeRepository challenges, UserPhoneRepository phones, SmsSender smsSender,
            PasswordEncoder passwordEncoder, JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
            Clock clock, MessageSource messages, RequestLocale requestLocale) {
        this.messages = messages;
        this.requestLocale = requestLocale;
        this.challenges = challenges;
        this.phones = phones;
        this.smsSender = smsSender;
        this.passwordEncoder = passwordEncoder;
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    record PhoneStatus(String phone, boolean verified, String maskedPhone) {
    }

    /** {@code resendAfterSeconds}: how long the next request for this number would be refused. */
    record SentCode(String maskedPhone, long resendAfterSeconds, Instant expiresAt) {
    }

    PhoneStatus current(UUID userId) {
        return phones.findById(userId)
                .filter(UserPhone::isVerified)
                .map(row -> new PhoneStatus(row.getPhoneE164(), true, PhoneNumbers.mask(row.getPhoneE164())))
                .orElse(new PhoneStatus(null, false, null));
    }

    /**
     * Order under the lock: verified elsewhere (409), daily cap, hourly cap, cooldown (429), then the
     * member's open code is invalidated and the new one stored and sent.
     */
    SentCode sendCode(UUID userId, String rawPhone) {
        String phone = PhoneNumbers.normalize(rawPhone)
                .orElseThrow(() -> new IllegalArgumentException("Not a Vietnamese mobile number"));
        Instant now = now();
        long resendAfterSeconds = transaction.execute(status -> {
            jdbc.query("SELECT pg_advisory_xact_lock(hashtext(?))", (RowCallbackHandler) row -> { }, phone);
            if (phones.existsByPhoneE164AndVerifiedAtIsNotNullAndUserIdNot(phone, userId)) {
                LOG.info("Phone code send refused: userId={} phone={} outcome=number_taken",
                        userId, PhoneNumbers.forLog(phone));
                throw new ConflictException(NUMBER_TAKEN);
            }
            List<Instant> sends = new ArrayList<>(challenges.findSendTimesSince(phone, now.minus(COUNTED_SENDS)));
            Decision decision = OtpSendPolicy.decide(sends, now);
            if (decision.outcome() != OtpSendPolicy.Outcome.ALLOW) {
                throw refusal(userId, phone, decision);
            }
            challenges.invalidateOpen(userId, now);
            String code = String.format("%06d", random.nextInt(1_000_000));
            challenges.save(new PhoneChallenge(UUID.randomUUID(), userId, phone, passwordEncoder.encode(code),
                    now.plus(CODE_TTL), now));
            smsSender.send(phone, messages.getMessage(new LocalizedMessage("sms.otp.body", code),
                    requestLocale.current()));
            sends.add(now);
            return OtpSendPolicy.decide(sends, now).retryAfterSeconds();
        });
        LOG.info("Phone code sent: userId={} phone={} outcome=sent", userId, PhoneNumbers.forLog(phone));
        return new SentCode(PhoneNumbers.mask(phone), resendAfterSeconds, now.plus(CODE_TTL));
    }

    // Postgres keeps microseconds: a nanosecond "now" would sit just before a stored time it rounded up.
    private Instant now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * Checks the code against the member's latest challenge. A wrong entry is counted in its own
     * committed transaction before the refusal is thrown, so the count survives the error response.
     */
    void verify(UUID userId, String code) {
        Instant now = now();
        Verification verification;
        try {
            verification = transaction.execute(status -> verifyLatest(userId, code, now));
        } catch (DataIntegrityViolationException e) {
            if (!isVerifiedNumberTaken(e)) {
                throw e;
            }
            verification = new Verification(Result.NUMBER_TAKEN, null, 0);
        }
        String phone = verification.phone() == null ? "none" : PhoneNumbers.forLog(verification.phone());
        LOG.info("Phone code checked: userId={} phone={} outcome={}", userId, phone,
                verification.result().name().toLowerCase(Locale.ROOT));
        switch (verification.result()) {
            case VERIFIED -> {
                return;
            }
            case WRONG -> throw new UnprocessableEntityException(
                    new LocalizedMessage("phone.otp.wrong", verification.triesLeft()), "OTP_INVALID");
            case LOCKED -> throw new UnprocessableEntityException(new LocalizedMessage("phone.otp.locked"),
                    "OTP_LOCKED");
            case EXPIRED -> throw new UnprocessableEntityException(new LocalizedMessage("phone.otp.expired"),
                    "OTP_EXPIRED");
            case STALE -> throw new UnprocessableEntityException(new LocalizedMessage("phone.otp.stale"),
                    "OTP_INVALID");
            case NUMBER_TAKEN -> throw new ConflictException(NUMBER_TAKEN);
            default -> throw new IllegalStateException("Unhandled verification result " + verification.result());
        }
    }

    private Verification verifyLatest(UUID userId, String code, Instant now) {
        Optional<PhoneChallenge> latest = challenges
                .findFirstByUserIdAndConsumedAtIsNullAndInvalidatedAtIsNullOrderByCreatedAtDesc(userId)
                .or(() -> challenges.findFirstByUserIdOrderByCreatedAtDesc(userId));
        if (latest.isEmpty()) {
            return new Verification(Result.STALE, null, 0);
        }
        PhoneChallenge challenge = latest.get();
        String phone = challenge.getPhoneE164();
        if (challenge.isLocked()) {
            return new Verification(Result.LOCKED, phone, 0);
        }
        if (!challenge.isOpen()) {
            return new Verification(Result.STALE, phone, 0);
        }
        if (challenge.isExpired(now)) {
            return new Verification(Result.EXPIRED, phone, 0);
        }
        if (!passwordEncoder.matches(code, challenge.getCodeHash())) {
            return wrongEntry(userId, challenge, code, now);
        }
        if (phones.existsByPhoneE164AndVerifiedAtIsNotNullAndUserIdNot(phone, userId)) {
            return new Verification(Result.NUMBER_TAKEN, phone, 0);
        }
        if (challenges.consume(challenge.getId(), now) == 0) {
            return new Verification(lockedOrStale(challenge.getId()), phone, 0);
        }
        UserPhone row = phones.findById(userId).orElseGet(() -> new UserPhone(userId, phone, now));
        row.verify(phone, now);
        phones.saveAndFlush(row);
        return new Verification(Result.VERIFIED, phone, 0);
    }

    private Verification wrongEntry(UUID userId, PhoneChallenge challenge, String code, Instant now) {
        String phone = challenge.getPhoneE164();
        boolean earlierCode = challenges.findTop5ByUserIdAndIdNotOrderByCreatedAtDesc(userId, challenge.getId())
                .stream()
                .anyMatch(earlier -> passwordEncoder.matches(code, earlier.getCodeHash()));
        if (earlierCode) {
            return new Verification(Result.STALE, phone, 0);
        }
        if (challenges.countWrongAttempt(challenge.getId()) == 0) {
            return new Verification(lockedOrStale(challenge.getId()), phone, 0);
        }
        int attempts = challenges.findAttempts(challenge.getId());
        if (attempts >= PhoneChallenge.MAX_ATTEMPTS) {
            challenges.invalidate(challenge.getId(), now);
            return new Verification(Result.LOCKED, phone, 0);
        }
        return new Verification(Result.WRONG, phone, PhoneChallenge.MAX_ATTEMPTS - attempts);
    }

    /** A conditional update changed nothing: a concurrent request locked or used the code first. */
    private Result lockedOrStale(UUID challengeId) {
        return challenges.findAttempts(challengeId) >= PhoneChallenge.MAX_ATTEMPTS ? Result.LOCKED : Result.STALE;
    }

    /** Another account verified the number between our check and our write. */
    private static boolean isVerifiedNumberTaken(DataIntegrityViolationException ex) {
        String message = NestedExceptionUtils.getMostSpecificCause(ex).getMessage();
        return message != null && message.contains(VERIFIED_NUMBER_UNIQUE_INDEX);
    }

    private static OtpRateLimitedException refusal(UUID userId, String phone, Decision decision) {
        long seconds = decision.retryAfterSeconds();
        LOG.info("Phone code send refused: userId={} phone={} outcome={} retryAfterSeconds={}", userId,
                PhoneNumbers.forLog(phone), decision.outcome().name().toLowerCase(Locale.ROOT), seconds);
        return switch (decision.outcome()) {
            case COOLDOWN -> new OtpRateLimitedException("OTP_COOLDOWN",
                    new LocalizedMessage("phone.otp.cooldown", seconds), seconds);
            case HOURLY_LIMIT -> new OtpRateLimitedException("OTP_HOURLY_LIMIT",
                    new LocalizedMessage("phone.otp.hourlyLimit", Math.max(1, (seconds + 59) / 60)), seconds);
            case DAILY_LIMIT -> new OtpRateLimitedException("OTP_DAILY_LIMIT",
                    new LocalizedMessage("phone.otp.dailyLimit"), seconds);
            default -> throw new IllegalStateException("Not a refusal: " + decision.outcome());
        };
    }

    private enum Result {
        VERIFIED,
        WRONG,
        LOCKED,
        EXPIRED,
        STALE,
        NUMBER_TAKEN
    }

    private record Verification(Result result, String phone, int triesLeft) {
    }
}
