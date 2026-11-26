package com.flowcortex.app.billing;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.AbstractPostgresBootTest;
import com.flowcortex.billing.api.PaymentStatusChangedEvent;
import com.flowcortex.billing.ledger.OpenPayment;
import com.flowcortex.billing.ledger.OpenedPayment;
import com.flowcortex.billing.ledger.PaymentLedger;
import com.flowcortex.billing.provider.PaymentInstrument;
import com.flowcortex.billing.reconcile.AgeingReconcileJob;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A plan purchase whose callback never came and is settled by the reconciliation from the provider's answer
 * gets its period and its holding exactly as a callback-settled one does.
 */
@SpringBootTest(properties = "flowcortex.billing.reconcile.ageing.enabled=true")
@Import(EntitlementGrantReconciliationIT.Wiring.class)
class EntitlementGrantReconciliationIT extends AbstractPostgresBootTest {

    private static final String PROVIDER = "momo";
    private static final String MERCHANT = "MOMOBKUN20180529";

    @TestConfiguration
    static class Wiring {

        @Bean
        ScriptedPaymentProvider scriptedMomo() {
            return new ScriptedPaymentProvider();
        }
    }

    private final UUID user = UUID.randomUUID();

    @Autowired
    private PaymentLedger ledger;

    @Autowired
    private AgeingReconcileJob ageing;

    @Autowired
    private ScriptedPaymentProvider provider;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        provider.reset();
    }

    @AfterEach
    void clean() {
        String mine = "(SELECT id FROM payment_transaction WHERE user_id = ?)";
        jdbc.update("DELETE FROM member_subscription_event WHERE user_id = ?", user);
        jdbc.update("DELETE FROM member_subscription WHERE user_id = ?", user);
        jdbc.update("DELETE FROM payment_drift_record WHERE transaction_id IN " + mine, user);
        jdbc.update("DELETE FROM payment_reconciliation_schedule WHERE transaction_id IN " + mine, user);
        jdbc.update("DELETE FROM payment_transaction_event WHERE transaction_id IN " + mine, user);
        jdbc.update("DELETE FROM event_publication WHERE event_type = ? AND serialized_event LIKE ?",
                PaymentStatusChangedEvent.class.getName(), "%" + user + "%");
        jdbc.update("DELETE FROM payment_transaction WHERE user_id = ?", user);
    }

    private Instant instant(Object timestamp) {
        return ((Timestamp) timestamp).toInstant();
    }

    @Test
    void aPlanPurchaseSettledByTheReconciliationStoresItsPeriodAndGrantsThePlan() {
        OpenedPayment opened = ledger.open(new OpenPayment(user, "plan_purchase", "basic:monthly", "Purchase",
                199_000L, PROVIDER, MERCHANT, Optional.of(PaymentInstrument.WALLET)));
        jdbc.update("UPDATE payment_transaction SET created_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minus(Duration.ofMinutes(20))), opened.transactionId());
        provider.answerAll("0", 199_000L, "4088878653");

        ageing.run();

        Map<String, Object> row = jdbc.queryForMap("SELECT status, succeeded_at, period_starts_at, period_renews_at "
                + "FROM payment_transaction WHERE id = ?", opened.transactionId());
        assertThat(row).containsEntry("status", "succeeded");
        Instant settled = instant(row.get("succeeded_at"));
        assertThat(instant(row.get("period_starts_at"))).isEqualTo(settled);
        assertThat(instant(row.get("period_renews_at"))).isEqualTo(
                settled.atZone(ZoneOffset.UTC).plusMonths(1).toInstant());
        Map<String, Object> held = jdbc.queryForMap("SELECT tier_id, billing_cycle, status, granted_from_transaction_id "
                + "FROM member_subscription WHERE user_id = ?", user);
        assertThat(held).containsEntry("tier_id", "basic").containsEntry("billing_cycle", "monthly")
                .containsEntry("status", "active").containsEntry("granted_from_transaction_id", opened.transactionId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM member_subscription_event WHERE user_id = ? "
                + "AND transaction_id = ? AND reason = 'settled_payment:basic'", Long.class, user,
                opened.transactionId())).isEqualTo(1L);
    }
}
