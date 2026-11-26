package com.flowcortex.app.billing;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.AbstractPostgresBootTest;
import com.flowcortex.billing.entitlement.PlanActivation;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class PlanActivationIT extends AbstractPostgresBootTest {

    private static final Instant GRANTED_AT = Instant.parse("2026-12-10T03:00:00Z");
    private static final Instant BOUNDARY = Instant.parse("2027-01-10T03:00:00Z");
    private static final Instant NEXT_END = Instant.parse("2027-02-10T03:00:00Z");

    private final List<UUID> members = new ArrayList<>();

    @Autowired
    private PlanActivation job;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MeterRegistry meters;

    @AfterEach
    void clean() {
        for (UUID member : members) {
            jdbc.update("DELETE FROM member_subscription_event WHERE user_id = ?", member);
            jdbc.update("DELETE FROM member_subscription WHERE user_id = ?", member);
            jdbc.update("DELETE FROM payment_transaction WHERE user_id = ?", member);
        }
    }

    /** A member holding basic until the boundary with advanced recorded for the following month. */
    private UUID memberWithNextPlan() {
        UUID member = UUID.randomUUID();
        members.add(member);
        UUID granted = payment(member, "basic:monthly", GRANTED_AT, BOUNDARY);
        UUID next = payment(member, "advanced:monthly", BOUNDARY, NEXT_END);
        jdbc.update("INSERT INTO member_subscription (user_id, tier_id, billing_cycle, status, billing_mode, started_at, "
                + "renews_at, granted_from_transaction_id, pending_tier_id, pending_billing_cycle, "
                + "pending_transaction_id) VALUES (?, 'basic', 'monthly', 'active', 'manual', ?, ?, ?, 'advanced', "
                + "'monthly', ?)", member, Timestamp.from(GRANTED_AT), Timestamp.from(BOUNDARY), granted, next);
        return member;
    }

    private UUID payment(UUID member, String itemRef, Instant startsAt, Instant renewsAt) {
        UUID id = UUID.randomUUID();
        String orderRef = "fc_" + id.toString().replace("-", "");
        jdbc.update("INSERT INTO payment_transaction (id, user_id, kind, item_ref, description, amount_vnd, provider, "
                + "merchant_code, order_ref, request_ref, status, created_at, updated_at, succeeded_at, period_starts_at, "
                + "period_renews_at) VALUES (?, ?, 'plan_purchase', ?, 'Plan', 199000, 'momo', 'M1', ?, ?, 'succeeded', "
                + "?, ?, ?, ?, ?)", id, member, itemRef, orderRef, UUID.randomUUID(), Timestamp.from(startsAt),
                Timestamp.from(startsAt), Timestamp.from(startsAt), Timestamp.from(startsAt), Timestamp.from(renewsAt));
        return id;
    }

    private Map<String, Object> holding(UUID member) {
        return jdbc.queryForMap("SELECT * FROM member_subscription WHERE user_id = ?", member);
    }

    private double activations() {
        return meters.find("billing.entitlement.activation").tag("trigger", "job").counters().stream()
                .mapToDouble(Counter::count).sum();
    }

    @Test
    void aNextPlanWhosePeriodHasBegunBecomesTheHeldPlan() {
        UUID member = memberWithNextPlan();
        double before = activations();

        int activated = job.runAt(Instant.parse("2027-01-10T03:05:00Z"));

        assertThat(activated).isEqualTo(1);
        Map<String, Object> held = holding(member);
        assertThat(held).containsEntry("tier_id", "advanced")
                .containsEntry("billing_cycle", "monthly")
                .containsEntry("status", "active")
                .containsEntry("pending_tier_id", null)
                .containsEntry("pending_transaction_id", null);
        assertThat(((Timestamp) held.get("started_at")).toInstant()).isEqualTo(BOUNDARY);
        assertThat(((Timestamp) held.get("renews_at")).toInstant()).isEqualTo(NEXT_END);
        UUID nextPayment = jdbc.queryForObject("SELECT id FROM payment_transaction WHERE user_id = ? AND item_ref = ?",
                UUID.class, member, "advanced:monthly");
        assertThat(held).containsEntry("granted_from_transaction_id", nextPayment);
        assertThat(jdbc.queryForList("SELECT from_tier_id, to_tier_id, from_status, to_status, reason, transaction_id "
                + "FROM member_subscription_event WHERE user_id = ?", member)).singleElement()
                .satisfies(event -> assertThat(event).containsEntry("from_tier_id", "basic")
                        .containsEntry("to_tier_id", "advanced")
                        .containsEntry("from_status", "active")
                        .containsEntry("to_status", "active")
                        .containsEntry("reason", "renewal_plan_activated:advanced")
                        .containsEntry("transaction_id", nextPayment));
        assertThat(activations()).isEqualTo(before + 1);
    }

    @Test
    void aNextPlanNotYetDueIsLeftAlone() {
        UUID member = memberWithNextPlan();

        int activated = job.runAt(Instant.parse("2027-01-09T00:00:00Z"));

        assertThat(activated).isZero();
        assertThat(holding(member)).containsEntry("tier_id", "basic");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM member_subscription_event WHERE user_id = ?", Long.class,
                member)).isZero();
    }

    @Test
    void aBacklogLargerThanABatchIsActivatedAHundredThenTheRest() {
        for (int i = 0; i < 150; i++) {
            memberWithNextPlan();
        }
        Instant due = Instant.parse("2027-01-10T03:05:00Z");

        assertThat(job.runAt(due)).isEqualTo(100);
        assertThat(job.runAt(due)).isEqualTo(50);
        assertThat(job.runAt(due)).isZero();
    }
}
