package com.flowcortex.user.phone;

import com.flowcortex.shared.web.ConflictException;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.RequestLocale;
import com.flowcortex.shared.web.UnprocessableEntityException;
import com.flowcortex.user.phone.PhoneSendPolicies.Refusal;
import com.flowcortex.user.phone.PhoneSendPolicies.Verdict;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
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
 * Proves a member owns a phone number with a 6-digit code. Sends are serialised by
 * transaction-scoped advisory locks on the member, then on the number, so every quota reads every
 * earlier send of both. Log lines carry the last three digits of a number and an {@code outcome=},
 * never the code.
 */
@Service
class PhoneVerificationService {

    static final Duration CODE_TTL = Duration.ofMinutes(5);
    static final LocalizedMessage NUMBER_TAKEN = new LocalizedMessage("phone.numberTaken");

    private static final Logger LOG = LoggerFactory.getLogger(PhoneVerificationService.class);
    private static final String LOCK = "SELECT pg_advisory_xact_lock(hashtext(?))";
    private static final String VERIFIED_NUMBER_UNIQUE_INDEX = "uq_user_phone_verified_number";

    private final PhoneChallengeRepository challenges;
    private final UserPhoneRepository phones;
    private final SmsSender smsSender;
    private final PhoneSendAttemptStore attempts;
    private final PhoneNumberHasher phoneHasher;
    private final PhoneSendIpLimiter ipLimiter;
    private final PhoneSendLimits limits;
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final MessageSource messages;
    private final RequestLocale requestLocale;
    private final SecureRandom random = new SecureRandom();

    /** Fails startup when the feature is on and no {@link SmsSender} is configured. */
    PhoneVerificationService(PhoneChallengeRepository challenges, UserPhoneRepository phones,
            ObjectProvider<SmsSender> smsSenders,
            @Value("${flowcortex.phone-verification.enabled:false}") boolean enabled,
            PhoneSendAttemptStore attempts, PhoneNumberHasher phoneHasher, PhoneSendIpLimiter ipLimiter,
            PhoneSendLimits limits, PasswordEncoder passwordEncoder, JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager, Clock clock, MessageSource messages,
            RequestLocale requestLocale) {
        this.messages = messages;
        this.requestLocale = requestLocale;
        this.smsSender = smsSenders.getIfAvailable();
        if (enabled && smsSender == null) {
            throw new IllegalStateException("flowcortex.phone-verification.enabled is true but no SMS sender is "
                    + "configured: flowcortex.sms.sender (FLOWCORTEX_SMS_SENDER) names none. 'outbox' keeps every "
                    + "code in the sms_outbox table and is for development stacks only.");
        }
        this.challenges = challenges;
        this.phones = phones;
        this.attempts = attempts;
        this.phoneHasher = phoneHasher;
        this.ipLimiter = ipLimiter;
        this.limits = limits;
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
     * Runs {@link PhoneSendPolicies#ORDER} under the locks and records the attempt, refused or not.
     * A refusal is thrown only after that row commits; an allowed request invalidates the member's
     * open code, then stores and sends the new one.
     */
    SentCode sendCode(UUID userId, String rawPhone, String clientIp) {
        String phone = PhoneNumbers.normalize(rawPhone)
                .orElseThrow(() -> new IllegalArgumentException("Not a Vietnamese mobile number"));
        String phoneHash = phoneHasher.hash(phone);
        Instant now = now();
        SendResult result = transaction.execute(status -> {
            jdbc.query(LOCK, (RowCallbackHandler) row -> { }, "phone-send-user:" + userId);
            jdbc.query(LOCK, (RowCallbackHandler) row -> { }, phone);
            Instant since = now.minus(PhoneSendPolicies.DAY);
            List<Instant> pairSends = new ArrayList<>(challenges.findSendTimesByUserSince(userId, phone, since));
            SendFacts facts = new SendFacts(phone, phoneHash, now,
                    attempts.countedByUserSince(userId, since),
                    () -> ipLimiter.tryAcquire(clientIp),
                    attempts.nthNewestSendSince(since, limits.globalSendsPerDay()),
                    phones.existsByPhoneE164AndVerifiedAtIsNotNullAndUserIdNot(phone, userId),
                    challenges.findSendTimesSince(phone, since),
                    pairSends);
            Optional<Verdict> refusal = PhoneSendPolicies.firstRefusal(facts, limits);
            attempts.record(userId, phoneHash, clientIp,
                    refusal.map(verdict -> outcomeOf(verdict.refusal())).orElse(PhoneSendAttemptStore.SENT), now);
            if (refusal.isPresent()) {
                return new SendResult(refusal.get(), 0);
            }
            challenges.invalidateOpen(userId, now);
            String code = String.format("%06d", random.nextInt(1_000_000));
            challenges.save(new PhoneChallenge(UUID.randomUUID(), userId, phone, passwordEncoder.encode(code),
                    now.plus(CODE_TTL), now));
            sender().send(phone, messages.getMessage(new LocalizedMessage("sms.otp.body", code),
                    requestLocale.current()));
            pairSends.add(now);
            return new SendResult(null, OtpSendPolicy.decide(pairSends, now).retryAfterSeconds());
        });
        if (result.refusal() != null) {
            throw refusal(userId, phone, result.refusal());
        }
        LOG.info("Phone code sent: userId={} phone={} outcome=sent", userId, PhoneNumbers.forLog(phone));
        return new SentCode(PhoneNumbers.mask(phone), result.resendAfterSeconds(), now.plus(CODE_TTL));
    }

    private SmsSender sender() {
        if (smsSender == null) {
            throw new IllegalStateException("No SMS sender is configured (flowcortex.sms.sender)");
        }
        return smsSender;
    }

    private static String outcomeOf(Refusal refusal) {
        return refusal.name().toLowerCase(Locale.ROOT);
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

    private static RuntimeException refusal(UUID userId, String phone, Verdict verdict) {
        long seconds = verdict.retryAfterSeconds();
        LOG.info("Phone code send refused: userId={} phone={} outcome={} retryAfterSeconds={}", userId,
                PhoneNumbers.forLog(phone), outcomeOf(verdict.refusal()), seconds);
        return switch (verdict.refusal()) {
            case COUNTRY_NOT_ALLOWED -> new UnprocessableEntityException(
                    new LocalizedMessage("phone.send.countryNotAllowed"), "PHONE_COUNTRY_NOT_ALLOWED");
            case NUMBER_TAKEN -> new ConflictException(NUMBER_TAKEN);
            case USER_DAILY_LIMIT -> rateLimited(verdict,
                    new LocalizedMessage("phone.send.userDailyLimit", waitText(seconds)));
            case USER_NUMBER_LIMIT -> rateLimited(verdict,
                    new LocalizedMessage("phone.send.userNumberLimit", waitText(seconds)));
            case IP_LIMIT -> rateLimited(verdict, new LocalizedMessage("phone.send.ipLimit", waitText(seconds)));
            case GLOBAL_LIMIT -> rateLimited(verdict,
                    new LocalizedMessage("phone.send.globalLimit", waitText(seconds)));
            case NUMBER_LIMIT -> rateLimited(verdict,
                    new LocalizedMessage("phone.send.numberLimit", waitText(seconds)));
            case COOLDOWN -> rateLimited(verdict, new LocalizedMessage("phone.otp.cooldown", seconds));
            case HOURLY_LIMIT -> rateLimited(verdict,
                    new LocalizedMessage("phone.otp.hourlyLimit", wholeMinutes(seconds)));
            case DAILY_LIMIT -> rateLimited(verdict, new LocalizedMessage("phone.otp.dailyLimit"));
        };
    }

    /** 429 whose {@code code} is {@code OTP_} plus the refusal name. */
    private static OtpRateLimitedException rateLimited(Verdict verdict, LocalizedMessage detail) {
        return new OtpRateLimitedException("OTP_" + verdict.refusal().name(), detail, verdict.retryAfterSeconds());
    }

    private static long wholeMinutes(long seconds) {
        return Math.max(1, (seconds + 59) / 60);
    }

    /**
     * Minutes up to two hours, whole hours (rounded up) beyond; a nested message the bundle resolves
     * in the same language as the sentence around it.
     */
    private static LocalizedMessage waitText(long seconds) {
        long minutes = wholeMinutes(seconds);
        if (minutes < 120) {
            return new LocalizedMessage("phone.wait.minutes", minutes);
        }
        return new LocalizedMessage("phone.wait.hours", (minutes + 59) / 60);
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

    /** {@code refusal} is null when the code was sent. */
    private record SendResult(Verdict refusal, long resendAfterSeconds) {
    }
}
