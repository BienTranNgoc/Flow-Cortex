package com.flowcortex.user.phone;

import com.flowcortex.user.phone.SendFacts.CountedAttempt;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The checks a send-code request goes through, in {@link #ORDER}; the first refusal wins. Every
 * check before {@link #numberTaken} ignores whether the number has an owner, so probing numbers
 * spends the prober's quota before it learns anything.
 */
final class PhoneSendPolicies {

    static final Duration DAY = Duration.ofHours(24);

    /** Why a request was refused; also the {@code outcome} its attempt row records, lower-cased. */
    enum Refusal {
        COUNTRY_NOT_ALLOWED,
        USER_DAILY_LIMIT,
        USER_NUMBER_LIMIT,
        IP_LIMIT,
        GLOBAL_LIMIT,
        NUMBER_TAKEN,
        NUMBER_LIMIT,
        DAILY_LIMIT,
        HOURLY_LIMIT,
        COOLDOWN
    }

    /** {@code retryAfterSeconds} is 0 when waiting does not help. */
    record Verdict(Refusal refusal, long retryAfterSeconds) {
    }

    @FunctionalInterface
    interface SendPolicy {

        Optional<Verdict> check(SendFacts facts, PhoneSendLimits limits);
    }

    static final List<SendPolicy> ORDER = List.of(
            PhoneSendPolicies::allowedCountry,
            PhoneSendPolicies::userDailySends,
            PhoneSendPolicies::userDistinctNumbers,
            PhoneSendPolicies::ipBurst,
            PhoneSendPolicies::globalBudget,
            PhoneSendPolicies::numberTaken,
            PhoneSendPolicies::numberCeiling,
            PhoneSendPolicies::memberLadder);

    private PhoneSendPolicies() {
    }

    static Optional<Verdict> firstRefusal(SendFacts facts, PhoneSendLimits limits) {
        for (SendPolicy policy : ORDER) {
            Optional<Verdict> verdict = policy.check(facts, limits);
            if (verdict.isPresent()) {
                return verdict;
            }
        }
        return Optional.empty();
    }

    // E.164 country codes are prefix-free, so a plain prefix match names exactly one country.
    static Optional<Verdict> allowedCountry(SendFacts facts, PhoneSendLimits limits) {
        boolean allowed = limits.allowedCountryCodes().stream().anyMatch(code -> facts.phoneE164().startsWith("+" + code));
        return allowed ? Optional.empty() : Optional.of(new Verdict(Refusal.COUNTRY_NOT_ALLOWED, 0));
    }

    static Optional<Verdict> userDailySends(SendFacts facts, PhoneSendLimits limits) {
        List<Instant> times = OtpSendPolicy.within(
                facts.userAttempts().stream().map(CountedAttempt::at).toList(), facts.now().minus(DAY));
        return capReached(times, limits.userSendsPerDay(), facts.now(), Refusal.USER_DAILY_LIMIT);
    }

    /** A number already tried today stays allowed; a new one needs a free slot. */
    static Optional<Verdict> userDistinctNumbers(SendFacts facts, PhoneSendLimits limits) {
        Instant windowStart = facts.now().minus(DAY);
        Map<String, Instant> lastTryPerNumber = new HashMap<>();
        for (CountedAttempt attempt : facts.userAttempts()) {
            if (attempt.at().isAfter(windowStart)) {
                lastTryPerNumber.merge(attempt.phoneHash(), attempt.at(), (a, b) -> a.isAfter(b) ? a : b);
            }
        }
        if (lastTryPerNumber.containsKey(facts.phoneHash())) {
            return Optional.empty();
        }
        // A slot frees when a number's last try leaves the window.
        return capReached(lastTryPerNumber.values().stream().sorted().toList(), limits.userNumbersPerDay(),
                facts.now(), Refusal.USER_NUMBER_LIMIT);
    }

    static Optional<Verdict> ipBurst(SendFacts facts, PhoneSendLimits limits) {
        Duration wait = facts.ipWait().get();
        if (wait.isZero() || wait.isNegative()) {
            return Optional.empty();
        }
        return Optional.of(new Verdict(Refusal.IP_LIMIT, OtpSendPolicy.secondsUntil(facts.now().plus(wait), facts.now())));
    }

    /** Lifts when the send that filled the budget leaves the window. */
    static Optional<Verdict> globalBudget(SendFacts facts, PhoneSendLimits limits) {
        Optional<Instant> filledBy = facts.globalBudgetFilledBy().filter(at -> at.isAfter(facts.now().minus(DAY)));
        if (filledBy.isEmpty()) {
            return Optional.empty();
        }
        Instant lifts = filledBy.get().plus(DAY);
        return Optional.of(new Verdict(Refusal.GLOBAL_LIMIT, OtpSendPolicy.secondsUntil(lifts, facts.now())));
    }

    static Optional<Verdict> numberTaken(SendFacts facts, PhoneSendLimits limits) {
        return facts.numberTakenByAnother() ? Optional.of(new Verdict(Refusal.NUMBER_TAKEN, 0)) : Optional.empty();
    }

    /** Codes to one handset from every member together: a high ceiling against SMS flooding. */
    static Optional<Verdict> numberCeiling(SendFacts facts, PhoneSendLimits limits) {
        List<Instant> times = OtpSendPolicy.within(facts.numberSends(), facts.now().minus(DAY));
        return capReached(times, limits.numberSendsPerDay(), facts.now(), Refusal.NUMBER_LIMIT);
    }

    /** The per-(member, number) daily and hourly caps and cooldown ladder of {@link OtpSendPolicy}. */
    static Optional<Verdict> memberLadder(SendFacts facts, PhoneSendLimits limits) {
        OtpSendPolicy.Decision decision = OtpSendPolicy.decide(facts.pairSends(), facts.now());
        return switch (decision.outcome()) {
            case ALLOW -> Optional.empty();
            case DAILY_LIMIT -> Optional.of(new Verdict(Refusal.DAILY_LIMIT, decision.retryAfterSeconds()));
            case HOURLY_LIMIT -> Optional.of(new Verdict(Refusal.HOURLY_LIMIT, decision.retryAfterSeconds()));
            case COOLDOWN -> Optional.of(new Verdict(Refusal.COOLDOWN, decision.retryAfterSeconds()));
        };
    }

    /** {@code oldestFirst} already inside the window; refused once it holds {@code cap} entries. */
    private static Optional<Verdict> capReached(List<Instant> oldestFirst, int cap, Instant now, Refusal refusal) {
        if (oldestFirst.size() < cap) {
            return Optional.empty();
        }
        Instant lifts = oldestFirst.get(oldestFirst.size() - cap).plus(DAY);
        return Optional.of(new Verdict(refusal, OtpSendPolicy.secondsUntil(lifts, now)));
    }
}
