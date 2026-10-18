package com.flowcortex.user.phone;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * What the send policies read about one send-code request, gathered under the send locks.
 *
 * @param userAttempts this member's {@code sent} and {@code number_taken} attempts in the last 24 h
 * @param ipWait takes a token from the client IP's bucket when called; zero when one was taken.
 *     Only the IP policy calls it, so a request refused earlier spends nothing there
 * @param globalBudgetFilledBy the global budget's worth of newest {@code sent} attempts from everyone
 *     in the last 24 h, the oldest of them; empty while fewer were sent
 * @param numberSends codes sent to this number in the last 24 h, whoever asked
 * @param pairSends codes this member had sent to this number in the last 24 h
 */
record SendFacts(
        String phoneE164,
        String phoneHash,
        Instant now,
        List<CountedAttempt> userAttempts,
        Supplier<Duration> ipWait,
        Optional<Instant> globalBudgetFilledBy,
        boolean numberTakenByAnother,
        List<Instant> numberSends,
        List<Instant> pairSends) {

    record CountedAttempt(String phoneHash, Instant at) {
    }
}
