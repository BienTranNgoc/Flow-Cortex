package com.flowcortex.chat.quota;

import com.flowcortex.chat.config.ChatProperties;
import com.flowcortex.chat.quota.QuotaLedger.Subject;
import com.flowcortex.chat.web.ChatProblemException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Message quotas: per member per minute and per day, and one ceiling over everyone per day. A day ends at
 * 00:00 Asia/Ho_Chi_Minh. A send that reaches the model is charged once; a turn that fails is refunded to
 * the day it was charged on.
 */
@Component("chatQuota")
public class ChatQuota {

    static final ZoneId DAY_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    static final String GLOBAL = "global";

    private final QuotaLedger ledger;
    private final ChatMinuteLimiter minuteLimiter;
    private final ChatProperties.Quota limits;
    private final Clock clock;

    ChatQuota(QuotaLedger ledger, ChatMinuteLimiter minuteLimiter, ChatProperties properties, Clock clock) {
        this.ledger = ledger;
        this.minuteLimiter = minuteLimiter;
        this.limits = properties.quota();
        this.clock = clock;
    }

    /**
     * Charges one send to the member and the system for today.
     * @return the day charged, to hand back to {@link #refund}
     * @throws ChatProblemException 429 {@code chat-quota} with the time the member can send again
     */
    public LocalDate charge(UUID memberId) {
        minuteLimiter.acquire(memberId);
        LocalDate day = LocalDate.now(clock.withZone(DAY_ZONE));
        List<Subject> subjects = List.of(new Subject(GLOBAL, limits.globalPerDay()),
                new Subject(memberKey(memberId), limits.memberPerDay()));
        if (ledger.charge(day, subjects).isPresent()) {
            throw ChatProblemException.quotaExhausted(resumeAt(day));
        }
        return day;
    }

    /** Gives back one send charged on {@code day}. */
    public void refund(UUID memberId, LocalDate day) {
        ledger.refund(day, List.of(GLOBAL, memberKey(memberId)));
    }

    private static Instant resumeAt(LocalDate day) {
        return day.plusDays(1).atStartOfDay(DAY_ZONE).toInstant();
    }

    private static String memberKey(UUID memberId) {
        return "member:" + memberId;
    }
}
