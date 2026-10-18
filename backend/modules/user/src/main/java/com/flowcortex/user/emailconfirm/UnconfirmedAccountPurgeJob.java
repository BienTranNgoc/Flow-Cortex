package com.flowcortex.user.emailconfirm;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Deletes self-registered accounts that never confirmed their email: created longer than {@link
 * EmailConfirmationProperties#unconfirmedAccountRetention()} ago and holding no link that could still
 * confirm them. A confirmed account is never touched. Their links, sessions and phone rows go with
 * them (ON DELETE CASCADE); audit rows keep the id until their own retention. Such an account never
 * received the signup grant, so the credit tables hold nothing for it. Runs in every instance; the
 * delete is idempotent. Works in batches of {@link EmailConfirmationProperties#purgeBatchSize()}, each
 * in its own transaction, until a batch comes back short.
 */
@Component
public class UnconfirmedAccountPurgeJob {

    private static final Logger LOG = LoggerFactory.getLogger(UnconfirmedAccountPurgeJob.class);

    private static final String PURGEABLE = "u.email_confirmed_at IS NULL AND u.created_at < :cutoff"
            + " AND NOT EXISTS (SELECT 1 FROM email_confirmation_token t"
            + " WHERE t.user_id = u.id AND t.used_at IS NULL AND t.expires_at > :now)";

    /**
     * Rows a re-registration, resend, confirmation or Google takeover holds are skipped; every one of
     * those locks the account row before it writes a link.
     */
    private static final String LOCK = "SELECT u.id FROM app_user u WHERE " + PURGEABLE
            + " LIMIT :batch FOR UPDATE SKIP LOCKED";

    /**
     * A new statement, so a fresh snapshot: a link committed between the lock statement's snapshot and
     * its lock (a resend that finished just before) is seen here and keeps the account.
     */
    private static final String DELETE = "DELETE FROM app_user u WHERE u.id IN (:ids) AND " + PURGEABLE;

    private final NamedParameterJdbcTemplate jdbc;
    private final EmailConfirmationProperties properties;
    private final Clock clock;
    private final TransactionTemplate transaction;

    UnconfirmedAccountPurgeJob(NamedParameterJdbcTemplate jdbc, EmailConfirmationProperties properties, Clock clock,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /**
     * A batch that locked fewer rows than its size ends the run. An account a batch locked but kept (a
     * link arrived) no longer matches, and a held one is skipped, so no batch repeats the last.
     */
    @Scheduled(fixedDelayString = "${flowcortex.auth.retention.interval}")
    public void purge() {
        Instant now = Instant.now(clock);
        int batchSize = properties.purgeBatchSize();
        int deleted = 0;
        int batches = 0;
        int locked;
        do {
            MapSqlParameterSource params = new MapSqlParameterSource()
                    .addValue("cutoff", Timestamp.from(now.minus(properties.unconfirmedAccountRetention())))
                    .addValue("now", Timestamp.from(now))
                    .addValue("batch", batchSize);
            int[] batch = transaction.execute(status -> {
                List<UUID> ids = jdbc.queryForList(LOCK, params, UUID.class);
                return new int[] {ids.size(), ids.isEmpty() ? 0 : jdbc.update(DELETE, params.addValue("ids", ids))};
            });
            locked = batch[0];
            deleted += batch[1];
            batches++;
        } while (locked == batchSize);
        LOG.info("email_confirmation outcome=unconfirmed_accounts_purged count={} batches={}", deleted, batches);
    }
}
