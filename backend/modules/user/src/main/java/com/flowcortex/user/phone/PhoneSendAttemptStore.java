package com.flowcortex.user.phone;

import com.flowcortex.user.phone.SendFacts.CountedAttempt;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** {@code phone_send_attempt}: one row per send-code request, holding a keyed hash of the number. */
@Component
class PhoneSendAttemptStore {

    static final String SENT = "sent";
    static final String NUMBER_TAKEN = "number_taken";

    private final JdbcTemplate jdbc;

    PhoneSendAttemptStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void record(UUID userId, String phoneHash, String clientIp, String outcome, Instant at) {
        jdbc.update("INSERT INTO phone_send_attempt (id, user_id, phone_hash, ip, outcome, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?)", UUID.randomUUID(), userId, phoneHash, clientIp, outcome,
                Timestamp.from(at));
    }

    /** The member's sends and number-taken answers after {@code since}, oldest first. */
    List<CountedAttempt> countedByUserSince(UUID userId, Instant since) {
        return jdbc.query("SELECT phone_hash, created_at FROM phone_send_attempt WHERE user_id = ? "
                        + "AND created_at > ? AND outcome IN ('" + SENT + "', '" + NUMBER_TAKEN + "') "
                        + "ORDER BY created_at",
                (row, i) -> new CountedAttempt(row.getString("phone_hash"), row.getTimestamp("created_at").toInstant()),
                userId, Timestamp.from(since));
    }

    /**
     * The {@code n}-th newest send after {@code since}, from every member; empty while fewer were sent.
     * One row is read, through the partial index on sent attempts.
     */
    Optional<Instant> nthNewestSendSince(Instant since, int n) {
        return jdbc.query("SELECT created_at FROM phone_send_attempt WHERE outcome = '" + SENT + "' "
                        + "AND created_at > ? ORDER BY created_at DESC OFFSET ? LIMIT 1",
                (row, i) -> row.getTimestamp("created_at").toInstant(), Timestamp.from(since), n - 1)
                .stream().findFirst();
    }
}
