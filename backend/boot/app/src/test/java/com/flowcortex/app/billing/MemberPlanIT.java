package com.flowcortex.app.billing;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.app.workflow.AbstractWorkflowHttpIT;
import com.flowcortex.billing.entitlement.MemberPlanReader;
import com.flowcortex.billing.entitlement.MemberPlanResponse;
import com.flowcortex.user.api.UserView;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class MemberPlanIT extends AbstractWorkflowHttpIT {

    private static final String PATH = "/api/v1/billing/plan";
    private static final Instant STARTED = Instant.parse("2026-12-10T03:00:00Z");
    private static final Instant BOUNDARY = Instant.parse("2027-01-10T03:00:00Z");
    private static final Instant NEXT_END = Instant.parse("2027-02-10T03:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final List<UUID> members = new ArrayList<>();

    @Autowired
    private MemberPlanReader reader;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void clean() {
        for (UUID member : members) {
            jdbc.update("DELETE FROM member_subscription WHERE user_id = ?", member);
            jdbc.update("DELETE FROM payment_transaction WHERE user_id = ?", member);
        }
    }

    private UUID payment(UUID member, String itemRef, Instant startsAt, Instant renewsAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO payment_transaction (id, user_id, kind, item_ref, description, amount_vnd, provider, "
                + "merchant_code, order_ref, request_ref, status, created_at, updated_at, succeeded_at, period_starts_at, "
                + "period_renews_at) VALUES (?, ?, 'plan_purchase', ?, 'Plan', 199000, 'momo', 'M1', ?, ?, 'succeeded', "
                + "?, ?, ?, ?, ?)", id, member, itemRef, "fc_" + id.toString().replace("-", ""), UUID.randomUUID(),
                Timestamp.from(startsAt), Timestamp.from(startsAt), Timestamp.from(startsAt), Timestamp.from(startsAt),
                Timestamp.from(renewsAt));
        return id;
    }

    /** Holds the tier for the period; with {@code nextTier} also records that plan for the following month. */
    private void hold(UUID member, String tier, Instant startsAt, Instant renewsAt, String nextTier, Instant nextEnd) {
        members.add(member);
        UUID granted = payment(member, tier + ":monthly", startsAt, renewsAt);
        UUID next = nextTier == null ? null : payment(member, nextTier + ":monthly", renewsAt, nextEnd);
        jdbc.update("INSERT INTO member_subscription (user_id, tier_id, billing_cycle, status, billing_mode, started_at, "
                + "renews_at, granted_from_transaction_id, pending_tier_id, pending_billing_cycle, "
                + "pending_transaction_id) VALUES (?, ?, 'monthly', 'active', 'manual', ?, ?, ?, ?, ?, ?)", member, tier,
                Timestamp.from(startsAt), Timestamp.from(renewsAt), granted, nextTier, nextTier == null ? null : "monthly",
                next);
    }

    @Test
    void aMemberWithNoHoldingReadsFree() {
        MemberPlanResponse plan = reader.read(UUID.randomUUID(), STARTED);

        assertThat(plan.tierId()).isEqualTo("free");
        assertThat(plan.name()).isEqualTo("Free");
        assertThat(plan.held()).isFalse();
        assertThat(plan.billingCycle()).isNull();
        assertThat(plan.billingMode()).isNull();
        assertThat(plan.startsAt()).isNull();
        assertThat(plan.renewsAt()).isNull();
        assertThat(plan.nextPlan()).isNull();
    }

    @Test
    void aMemberHoldingBasicWithAdvancedRecordedNextReadsBothBeforeTheBoundary() {
        UUID member = UUID.randomUUID();
        hold(member, "basic", STARTED, BOUNDARY, "advanced", NEXT_END);

        MemberPlanResponse plan = reader.read(member, Instant.parse("2026-12-20T00:00:00Z"));

        assertThat(plan.tierId()).isEqualTo("basic");
        assertThat(plan.name()).isEqualTo("Cơ bản");
        assertThat(plan.nameEn()).isEqualTo("Starter");
        assertThat(plan.held()).isTrue();
        assertThat(plan.billingCycle()).isEqualTo("monthly");
        assertThat(plan.billingMode()).isEqualTo("manual");
        assertThat(plan.startsAt()).isEqualTo(STARTED);
        assertThat(plan.renewsAt()).isEqualTo(BOUNDARY);
        assertThat(plan.nextPlan()).isNotNull();
        assertThat(plan.nextPlan().tierId()).isEqualTo("advanced");
        assertThat(plan.nextPlan().name()).isEqualTo("Nâng cao");
        assertThat(plan.nextPlan().nameEn()).isEqualTo("Advanced");
        assertThat(plan.nextPlan().billingCycle()).isEqualTo("monthly");
        assertThat(plan.nextPlan().startsAt()).isEqualTo(BOUNDARY);
        assertThat(plan.nextPlan().renewsAt()).isEqualTo(NEXT_END);
    }

    @Test
    void theNextPlanIsTheHeldPlanOnceItsPeriodHasBegunWithoutAnyWrite() {
        UUID member = UUID.randomUUID();
        hold(member, "basic", STARTED, BOUNDARY, "advanced", NEXT_END);

        MemberPlanResponse plan = reader.read(member, Instant.parse("2027-01-10T03:01:00Z"));

        assertThat(plan.tierId()).isEqualTo("advanced");
        assertThat(plan.name()).isEqualTo("Nâng cao");
        assertThat(plan.startsAt()).isEqualTo(BOUNDARY);
        assertThat(plan.renewsAt()).isEqualTo(NEXT_END);
        assertThat(plan.nextPlan()).isNull();
        assertThat(jdbc.queryForObject("SELECT tier_id FROM member_subscription WHERE user_id = ?", String.class, member))
                .isEqualTo("basic");
    }

    @Test
    void oneMembersPlanIsNotReadByAnother() {
        UUID holder = UUID.randomUUID();
        hold(holder, "professional", STARTED, BOUNDARY, null, null);

        assertThat(reader.read(holder, Instant.parse("2026-12-20T00:00:00Z")).tierId()).isEqualTo("professional");
        assertThat(reader.read(UUID.randomUUID(), Instant.parse("2026-12-20T00:00:00Z")).tierId()).isEqualTo("free");
    }

    @Test
    void theRouteAnswers401WithoutATokenAndTheCallersOwnPlanWithOne() throws Exception {
        UserView holder = newUser("plan-holder");
        UserView other = newUser("plan-other");
        Instant now = Instant.now();
        hold(holder.id(), "professional", now.minus(1, ChronoUnit.DAYS), now.plus(29, ChronoUnit.DAYS), null, null);

        ResponseEntity<String> anonymous = get(PATH, null, String.class);
        JsonNode mine = JSON.readTree(get(PATH, loginAs(holder), String.class).getBody());
        ResponseEntity<String> otherResponse = get(PATH, loginAs(other), String.class);
        JsonNode theirs = JSON.readTree(otherResponse.getBody());

        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(mine.get("tierId").asString()).isEqualTo("professional");
        assertThat(mine.get("held").asBoolean()).isTrue();
        assertThat(mine.get("billingCycle").asString()).isEqualTo("monthly");
        assertThat(mine.get("nextPlan").isNull()).isTrue();
        assertThat(otherResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(theirs.get("tierId").asString()).isEqualTo("free");
        assertThat(theirs.get("held").asBoolean()).isFalse();
        assertThat(theirs.get("renewsAt").isNull()).isTrue();
    }
}
