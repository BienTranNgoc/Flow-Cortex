package com.flowcortex.support.assistant;

import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.support.config.SupportProperties;
import com.flowcortex.support.web.SupportProblemException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Daily question quotas. A day ends at 00:00 Asia/Ho_Chi_Minh. Each charge is one conditional upsert
 * per subject, so concurrent questions can never pass a limit; all charges of one question commit
 * together or not at all, always in the same subject order so two questions cannot deadlock.
 */
@Component
class QuotaService {

    static final ZoneId DAY_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    static final String GLOBAL = "global";

    private static final Logger LOG = LoggerFactory.getLogger(QuotaService.class);

    private static final String CHARGE = "INSERT INTO support_quota_usage (subject_key, day, count) VALUES (?, ?, 1) "
            + "ON CONFLICT (subject_key, day) DO UPDATE SET count = support_quota_usage.count + 1 "
            + "WHERE support_quota_usage.count < ? RETURNING count";

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactions;
    private final SupportProperties.Assistant limits;
    private final Clock clock;
    private final AtomicReference<LocalDate> ceilingWarnedFor = new AtomicReference<>();

    QuotaService(JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager,
            SupportProperties properties, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.limits = properties.assistant();
        this.clock = clock;
    }

    record Subject(String key, int limit) {
    }

    /** What one question was charged, kept to refund it. */
    record Charge(LocalDate day, List<String> keys, int remaining) {
    }

    record Remaining(int remaining, Instant resetsAt) {
    }

    /**
     * Charges the global ceiling and the asker's subjects. Refuses with 503 {@code assistant-paused}
     * when the ceiling is used up and 429 {@code assistant-quota} when one of the asker's quotas is.
     */
    Charge charge(Asker asker) {
        return charge(asker, true);
    }

    /**
     * Charges only the asker's own subjects, for a question that reaches no provider and so leaves the
     * global ceiling (a count of provider calls) alone. Refuses with 429 like {@link #charge}.
     */
    Charge chargeAskerOnly(Asker asker) {
        return charge(asker, false);
    }

    private Charge charge(Asker asker, boolean globalCeiling) {
        LocalDate day = today();
        List<Subject> subjects = subjects(asker);
        Integer remaining = transactions.execute(status -> {
            if (globalCeiling && increment(GLOBAL, day, limits.dailyCeiling()) == null) {
                throw paused(day);
            }
            int least = Integer.MAX_VALUE;
            for (Subject subject : subjects) {
                Integer count = increment(subject.key(), day, subject.limit());
                if (count == null) {
                    throw exhausted(resetsAt(day));
                }
                least = Math.min(least, subject.limit() - count);
            }
            return least;
        });
        List<String> keys = new ArrayList<>();
        if (globalCeiling) {
            keys.add(GLOBAL);
        }
        subjects.forEach(subject -> keys.add(subject.key()));
        return new Charge(day, keys, remaining == null ? 0 : remaining);
    }

    /** Gives back a charge whose question went unanswered; runs in its own transaction. */
    void refund(Charge charge) {
        transactions.executeWithoutResult(status -> charge.keys().forEach(key -> jdbcTemplate.update(
                "UPDATE support_quota_usage SET count = count - 1 WHERE subject_key = ? AND day = ? AND count > 0",
                key, charge.day())));
    }

    /** The asker's questions left today without charging one. */
    Remaining peek(Asker asker) {
        LocalDate day = today();
        int least = Integer.MAX_VALUE;
        for (Subject subject : subjects(asker)) {
            least = Math.min(least, Math.max(0, subject.limit() - used(subject.key(), day)));
        }
        return new Remaining(least, resetsAt(day));
    }

    int dailyCeiling() {
        return limits.dailyCeiling();
    }

    boolean ceilingReachedToday() {
        return used(GLOBAL, today()) >= limits.dailyCeiling();
    }

    /** Refuses with 503 {@code assistant-paused} while today's ceiling is used up, without charging. */
    void refuseWhenPaused() {
        LocalDate day = today();
        if (used(GLOBAL, day) >= limits.dailyCeiling()) {
            throw paused(day);
        }
    }

    private SupportProblemException paused(LocalDate day) {
        warnCeilingOnce(day);
        return new SupportProblemException(HttpStatus.SERVICE_UNAVAILABLE, "assistant-paused",
                new LocalizedMessage("support.assistant.paused")).with("retryAt", resetsAt(day));
    }

    LocalDate today() {
        return LocalDate.now(clock.withZone(DAY_ZONE));
    }

    static Instant resetsAt(LocalDate day) {
        return day.plusDays(1).atStartOfDay(DAY_ZONE).toInstant();
    }

    static SupportProblemException exhausted(Instant retryAt) {
        return new SupportProblemException(HttpStatus.TOO_MANY_REQUESTS, "assistant-quota",
                new LocalizedMessage("support.assistant.quota")).with("retryAt", retryAt);
    }

    private List<Subject> subjects(Asker asker) {
        return switch (asker) {
            case Asker.Member member -> List.of(new Subject(member.subjectKey(), limits.memberDailyQuota()));
            case Asker.Guest guest -> List.of(new Subject(guest.subjectKey(), limits.guestDailyQuota()),
                    new Subject(guest.networkKey(), limits.guestNetworkDailyQuota()));
        };
    }

    private Integer increment(String key, LocalDate day, int limit) {
        List<Integer> counts = jdbcTemplate.queryForList(CHARGE, Integer.class, key, day, limit);
        return counts.isEmpty() ? null : counts.getFirst();
    }

    private int used(String key, LocalDate day) {
        List<Integer> counts = jdbcTemplate.queryForList(
                "SELECT count FROM support_quota_usage WHERE subject_key = ? AND day = ?", Integer.class, key, day);
        return counts.isEmpty() ? 0 : counts.getFirst();
    }

    private void warnCeilingOnce(LocalDate day) {
        LocalDate warned = ceilingWarnedFor.get();
        if (!day.equals(warned) && ceilingWarnedFor.compareAndSet(warned, day)) {
            LOG.warn("support.assistant.ceiling outcome=reached ceiling={}", limits.dailyCeiling());
        }
    }
}
