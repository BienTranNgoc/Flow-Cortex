package com.flowcortex.user.phone;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.user.phone.PhoneSendPolicies.Refusal;
import com.flowcortex.user.phone.PhoneSendPolicies.Verdict;
import com.flowcortex.user.phone.SendFacts.CountedAttempt;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class PhoneSendPoliciesTest {

    private static final Instant NOW = Instant.parse("2026-10-16T08:00:00Z");
    private static final String PHONE = "+84912345678";
    private static final String HASH = "a".repeat(64);
    private static final long HOUR = 3600;

    private static final PhoneSendLimits LIMITS = limits(List.of("84"), 2000);

    @Test
    void aNumberOutsideTheAllowedCountriesIsRefusedWithoutAWait() {
        assertThat(PhoneSendPolicies.allowedCountry(facts().build(), limits(List.of("1", "44"), 2000)))
                .contains(new Verdict(Refusal.COUNTRY_NOT_ALLOWED, 0));
        assertThat(PhoneSendPolicies.allowedCountry(facts().build(), limits(List.of("1", "84"), 2000))).isEmpty();
    }

    @Test
    void theEleventhCountedAttemptOfTheDayIsRefusedUntilTheOldestLeavesTheWindow() {
        List<CountedAttempt> ten = IntStream.range(0, 10)
                .mapToObj(i -> new CountedAttempt(HASH, NOW.minusSeconds((20 - 2L * i) * HOUR)))
                .toList();

        assertThat(PhoneSendPolicies.userDailySends(facts().userAttempts(ten).build(), LIMITS))
                .contains(new Verdict(Refusal.USER_DAILY_LIMIT, 4 * HOUR));
        assertThat(PhoneSendPolicies.userDailySends(facts().userAttempts(ten.subList(1, 10)).build(), LIMITS))
                .isEmpty();
    }

    @Test
    void aFourthDistinctNumberIsRefusedButANumberAlreadyTriedTodayIsNot() {
        List<CountedAttempt> threeOthers = List.of(
                new CountedAttempt("a", NOW.minusSeconds(5 * HOUR)),
                new CountedAttempt("a", NOW.minusSeconds(2 * HOUR)),
                new CountedAttempt("b", NOW.minusSeconds(3 * HOUR)),
                new CountedAttempt("c", NOW.minusSeconds(HOUR)));

        assertThat(PhoneSendPolicies.userDistinctNumbers(facts().userAttempts(threeOthers).build(), LIMITS))
                .contains(new Verdict(Refusal.USER_NUMBER_LIMIT, 21 * HOUR));

        List<CountedAttempt> includingThisOne = new ArrayList<>(threeOthers);
        includingThisOne.add(new CountedAttempt(HASH, NOW.minusSeconds(HOUR)));
        assertThat(PhoneSendPolicies.userDistinctNumbers(facts().userAttempts(includingThisOne).build(), LIMITS))
                .isEmpty();
    }

    @Test
    void anEmptyIpBucketRefusesForAsLongAsTheRefillTakes() {
        assertThat(PhoneSendPolicies.ipBurst(facts().ipWait(() -> Duration.ofMillis(89_001)).build(), LIMITS))
                .contains(new Verdict(Refusal.IP_LIMIT, 90));
        assertThat(PhoneSendPolicies.ipBurst(facts().build(), LIMITS)).isEmpty();
    }

    @Test
    void theGlobalBudgetLiftsWhenTheOldestCountedSendLeavesTheWindow() {
        List<Instant> newestFirst = List.of(NOW.minusSeconds(HOUR), NOW.minusSeconds(3 * HOUR), NOW.minusSeconds(5 * HOUR));

        PhoneSendLimits budgetOfTwo = limits(List.of("84"), 2);
        PhoneSendLimits budgetOfFour = limits(List.of("84"), 4);

        assertThat(PhoneSendPolicies.globalBudget(facts().limits(budgetOfTwo).globalSends(newestFirst).build(),
                budgetOfTwo)).contains(new Verdict(Refusal.GLOBAL_LIMIT, 21 * HOUR));
        assertThat(PhoneSendPolicies.globalBudget(facts().limits(budgetOfFour).globalSends(newestFirst).build(),
                budgetOfFour)).isEmpty();
    }

    @Test
    void aBudgetFilledBeforeTheWindowNoLongerRefuses() {
        SendFacts filledADayAgo = facts().build(Optional.of(NOW.minus(PhoneSendPolicies.DAY)));

        assertThat(PhoneSendPolicies.globalBudget(filledADayAgo, LIMITS)).isEmpty();
        assertThat(PhoneSendPolicies.globalBudget(facts().build(Optional.of(NOW.minusSeconds(1))), LIMITS))
                .contains(new Verdict(Refusal.GLOBAL_LIMIT, 24 * HOUR - 1));
    }

    @Test
    void aNumberVerifiedOnAnotherAccountIsRefused() {
        assertThat(PhoneSendPolicies.numberTaken(facts().numberTaken(true).build(), LIMITS))
                .contains(new Verdict(Refusal.NUMBER_TAKEN, 0));
        assertThat(PhoneSendPolicies.numberTaken(facts().build(), LIMITS)).isEmpty();
    }

    @Test
    void twentyCodesToOneNumberFromAnyoneReachTheCeiling() {
        List<Instant> twenty = IntStream.range(0, 20).mapToObj(i -> NOW.minusSeconds((20 - i) * HOUR)).toList();

        assertThat(PhoneSendPolicies.numberCeiling(facts().numberSends(twenty).build(), LIMITS))
                .contains(new Verdict(Refusal.NUMBER_LIMIT, 4 * HOUR));
        assertThat(PhoneSendPolicies.numberCeiling(facts().numberSends(twenty.subList(1, 20)).build(), LIMITS))
                .isEmpty();
    }

    @Test
    void theMembersOwnEarlierSendToTheNumberStartsTheCooldown() {
        assertThat(PhoneSendPolicies.memberLadder(facts().pairSends(List.of(NOW.minusSeconds(59))).build(), LIMITS))
                .contains(new Verdict(Refusal.COOLDOWN, 1));
        assertThat(PhoneSendPolicies.memberLadder(facts().pairSends(List.of(NOW.minusSeconds(60))).build(), LIMITS))
                .isEmpty();
    }

    /** Every check refuses at first; clearing them one by one shows each refusal in turn, in order. */
    @Test
    void theChecksRunInTheirDeclaredOrder() {
        List<CountedAttempt> elevenOnThreeOtherNumbers = IntStream.range(0, 11)
                .mapToObj(i -> new CountedAttempt(String.valueOf(i % 3), NOW.minusSeconds(HOUR + i)))
                .toList();
        List<Instant> twentySends = IntStream.range(0, 20).mapToObj(i -> NOW.minusSeconds(HOUR + i)).toList();
        FactsBuilder facts = facts()
                .userAttempts(elevenOnThreeOtherNumbers)
                .ipWait(() -> Duration.ofSeconds(30))
                .globalSends(List.of(NOW.minusSeconds(10)))
                .numberTaken(true)
                .numberSends(twentySends)
                .pairSends(List.of(NOW.minusSeconds(10)));
        PhoneSendLimits limits = limits(List.of("1"), 1);
        List<Refusal> seen = new ArrayList<>();
        List<Runnable> clearInOrder = List.of(
                () -> { },
                () -> facts.limits = limits(List.of("84"), 1),
                () -> facts.userAttempts(elevenOnThreeOtherNumbers.subList(0, 3)),
                () -> facts.userAttempts(List.of()),
                () -> facts.ipWait(() -> Duration.ZERO),
                () -> facts.limits = limits(List.of("84"), 2000),
                () -> facts.numberTaken(false),
                () -> facts.numberSends(List.of()));
        facts.limits = limits;

        for (Runnable clear : clearInOrder) {
            clear.run();
            PhoneSendPolicies.firstRefusal(facts.build(), facts.limits).map(Verdict::refusal).ifPresent(seen::add);
        }

        assertThat(seen).containsExactly(Refusal.COUNTRY_NOT_ALLOWED, Refusal.USER_DAILY_LIMIT,
                Refusal.USER_NUMBER_LIMIT, Refusal.IP_LIMIT, Refusal.GLOBAL_LIMIT, Refusal.NUMBER_TAKEN,
                Refusal.NUMBER_LIMIT, Refusal.COOLDOWN);
        facts.pairSends(List.of());
        assertThat(PhoneSendPolicies.firstRefusal(facts.build(), facts.limits)).isEmpty();
    }

    @Test
    void aRequestRefusedByAMemberQuotaTakesNoIpToken() {
        AtomicInteger ipCalls = new AtomicInteger();
        List<CountedAttempt> threeOthers = List.of(new CountedAttempt("a", NOW.minusSeconds(HOUR)),
                new CountedAttempt("b", NOW.minusSeconds(HOUR)), new CountedAttempt("c", NOW.minusSeconds(HOUR)));

        Optional<Verdict> verdict = PhoneSendPolicies.firstRefusal(facts().userAttempts(threeOthers)
                .ipWait(() -> {
                    ipCalls.incrementAndGet();
                    return Duration.ZERO;
                }).build(), LIMITS);

        assertThat(verdict.map(Verdict::refusal)).contains(Refusal.USER_NUMBER_LIMIT);
        assertThat(ipCalls).hasValue(0);
    }

    private static PhoneSendLimits limits(List<String> countries, int globalBudget) {
        return new PhoneSendLimits(countries, 10, 3, new PhoneSendLimits.IpLimit(30, Duration.ofHours(1)), 20,
                globalBudget);
    }

    private static FactsBuilder facts() {
        return new FactsBuilder();
    }

    /** Facts that pass every check until a setter says otherwise. */
    private static final class FactsBuilder {

        private PhoneSendLimits limits = LIMITS;
        private List<CountedAttempt> userAttempts = List.of();
        private Supplier<Duration> ipWait = () -> Duration.ZERO;
        private List<Instant> globalSends = List.of();
        private boolean numberTaken;
        private List<Instant> numberSends = List.of();
        private List<Instant> pairSends = List.of();

        FactsBuilder userAttempts(List<CountedAttempt> value) {
            userAttempts = value;
            return this;
        }

        FactsBuilder ipWait(Supplier<Duration> value) {
            ipWait = value;
            return this;
        }

        FactsBuilder limits(PhoneSendLimits value) {
            limits = value;
            return this;
        }

        /** Every send from everyone; {@link #build} reads from them what the store's query returns. */
        FactsBuilder globalSends(List<Instant> value) {
            globalSends = value;
            return this;
        }

        FactsBuilder numberTaken(boolean value) {
            numberTaken = value;
            return this;
        }

        FactsBuilder numberSends(List<Instant> value) {
            numberSends = value;
            return this;
        }

        FactsBuilder pairSends(List<Instant> value) {
            pairSends = value;
            return this;
        }

        SendFacts build() {
            return build(nthNewestInWindow(globalSends, limits.globalSendsPerDay()));
        }

        SendFacts build(Optional<Instant> globalBudgetFilledBy) {
            return new SendFacts(PHONE, HASH, NOW, userAttempts, ipWait, globalBudgetFilledBy, numberTaken,
                    numberSends, pairSends);
        }

        private static Optional<Instant> nthNewestInWindow(List<Instant> sends, int n) {
            List<Instant> newestFirst = sends.stream()
                    .filter(at -> at.isAfter(NOW.minus(PhoneSendPolicies.DAY)))
                    .sorted(Comparator.reverseOrder())
                    .toList();
            return newestFirst.size() < n ? Optional.empty() : Optional.of(newestFirst.get(n - 1));
        }
    }
}
