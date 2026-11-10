package com.flowcortex.chat.quota;

import java.time.LocalDate;
import java.util.List;
import java.util.OptionalInt;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code chat_quota_usage} counters. Each charge is one conditional upsert per subject inside its own
 * transaction, so concurrent sends can never pass a limit, a refused subject leaves nothing charged, and a
 * charge survives the send transaction that asked for it rolling back (the caller then refunds it).
 */
@Component("chatQuotaLedger")
class JdbcQuotaLedger implements QuotaLedger {

    private static final String CHARGE = "INSERT INTO chat_quota_usage (subject, day, count) VALUES (?, ?, 1) "
            + "ON CONFLICT (subject, day) DO UPDATE SET count = chat_quota_usage.count + 1 "
            + "WHERE chat_quota_usage.count < ? RETURNING count";

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactions;

    JdbcQuotaLedger(JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public OptionalInt charge(LocalDate day, List<Subject> subjects) {
        return transactions.execute(status -> {
            for (int i = 0; i < subjects.size(); i++) {
                Subject subject = subjects.get(i);
                if (jdbcTemplate.queryForList(CHARGE, Integer.class, subject.key(), day, subject.limit()).isEmpty()) {
                    status.setRollbackOnly();
                    return OptionalInt.of(i);
                }
            }
            return OptionalInt.empty();
        });
    }

    @Override
    public void refund(LocalDate day, List<String> keys) {
        transactions.executeWithoutResult(status -> keys.forEach(key -> jdbcTemplate.update(
                "UPDATE chat_quota_usage SET count = count - 1 WHERE subject = ? AND day = ? AND count > 0",
                key, day)));
    }
}
