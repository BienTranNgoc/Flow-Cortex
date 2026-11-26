package com.flowcortex.app.billing;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.AbstractPostgresBootTest;
import com.flowcortex.billing.ledger.OpenPayment;
import com.flowcortex.billing.ledger.OpenedPayment;
import com.flowcortex.billing.ledger.PaymentLedger;
import com.flowcortex.billing.provider.PaymentInstrument;
import com.flowcortex.billing.reconcile.AgeingReconcileJob;
import com.flowcortex.billing.reconcile.DailyReconcileJob;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
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

/** The passes switched off (the test default): they ask nothing, but the ageing pass still measures. */
@SpringBootTest
@Import(PaymentReconciliationSwitchedOffIT.Wiring.class)
class PaymentReconciliationSwitchedOffIT extends AbstractPostgresBootTest {

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
    private DailyReconcileJob daily;

    @Autowired
    private ScriptedPaymentProvider provider;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MeterRegistry meters;

    @BeforeEach
    void reset() {
        provider.reset();
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM payment_reconciliation_schedule WHERE transaction_id IN "
                + "(SELECT id FROM payment_transaction WHERE user_id = ?)", user);
        jdbc.update("DELETE FROM payment_transaction_event WHERE transaction_id IN "
                + "(SELECT id FROM payment_transaction WHERE user_id = ?)", user);
        jdbc.update("DELETE FROM payment_transaction WHERE user_id = ?", user);
    }

    private OpenedPayment open(Duration age) {
        OpenedPayment opened = ledger.open(new OpenPayment(user, "topup", "pkg-50k", "Top-up", 50_000L, "momo",
                "MOMOBKUN20180529", Optional.of(PaymentInstrument.WALLET)));
        jdbc.update("UPDATE payment_transaction SET created_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minus(age)), opened.transactionId());
        return opened;
    }

    private long disabledPasses(String pass) {
        return meters.find("payment.reconcile.pass").tags("pass", pass, "outcome", "disabled").timers().stream()
                .mapToLong(timer -> timer.count()).sum();
    }

    @Test
    void switchedOffPassesAskNothingAndChangeNoRowButTheAgeingPassStillMeasures() {
        OpenedPayment twenty = open(Duration.ofMinutes(20));
        OpenedPayment fifty = open(Duration.ofMinutes(50));
        long unresolved = jdbc.queryForObject("SELECT count(*) FROM payment_transaction WHERE status = 'pending' "
                + "AND created_at <= now() - interval '15 minutes'", Long.class);
        long ageingBefore = disabledPasses("ageing");
        long dailyBefore = disabledPasses("daily");

        ageing.run();
        daily.run();

        assertThat(provider.queries()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_reconciliation_schedule WHERE transaction_id "
                + "IN (?, ?)", Long.class, twenty.transactionId(), fifty.transactionId())).isZero();
        assertThat(meters.get("payment.reconcile.unresolved").gauge().value()).isGreaterThanOrEqualTo(2.0)
                .isBetween((double) unresolved, (double) unresolved + 5);
        assertThat(meters.get("payment.reconcile.unresolved.oldest_age_seconds").gauge().value())
                .isGreaterThanOrEqualTo(3000.0);
        assertThat(disabledPasses("ageing")).isEqualTo(ageingBefore + 1);
        assertThat(disabledPasses("daily")).isEqualTo(dailyBefore + 1);
    }
}
