package com.flowcortex.app.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.user.api.UserView;
import com.flowcortex.workflow.api.NewWorkflowStep;
import com.flowcortex.workflow.api.WorkflowView;
import jakarta.persistence.EntityManagerFactory;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/** The credit figures of the run-history endpoints, with credits on. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
        "flowcortex.credits.enabled=true",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "flowcortex.workflow.reaper-interval-ms=3600000"})
@AutoConfigureTestRestTemplate
class RunHistoryCreditsEndpointsIT extends AbstractWorkflowHttpIT {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    record RowView(UUID id, Long durationSeconds, Long creditsCharged) {
    }

    record PageOf(List<RowView> items, int page, int size, long total) {
    }

    record TopView(String name, long runs, Long credits, Double averageCredits) {
    }

    record UsageView(long runs, Long creditsSpent, Long balance, List<TopView> topWorkflows) {
    }

    @Test
    void durationAndCreditsPerRowAndTheUsageSummary() {
        UserView m = newUser("hist-credits");
        awaitWallet(m.id());
        jdbc.update("UPDATE credit_wallet SET balance = 777 WHERE user_id = ?", m.id());
        WorkflowView w = newWorkflow(m.id(), false, new NewWorkflowStep("only", "echo", null));
        Instant start = Instant.now().minusSeconds(600);
        UUID charged = seedRun(w.id(), m.id(), start, start.plusSeconds(150));
        UUID free = seedRun(w.id(), m.id(), start.minusSeconds(60), start.minusSeconds(30));
        ledgerRow(m.id(), charged, "debit", 30, start.plusSeconds(1));
        ledgerRow(m.id(), charged, "refund", 10, start.plusSeconds(140));
        String token = loginAs(m);

        PageOf page = get("/api/v1/runs", token, PageOf.class).getBody();

        assertThat(page.items()).extracting(RowView::id).containsExactly(charged, free);
        assertThat(page.items().get(0).durationSeconds()).isEqualTo(150);
        assertThat(page.items().get(0).creditsCharged()).isEqualTo(20);
        assertThat(page.items().get(1).creditsCharged()).as("no ledger entry reads as 0").isZero();

        ResponseEntity<UsageView> usage = get("/api/v1/runs/usage-summary", token, UsageView.class);
        assertThat(usage.getBody().runs()).isEqualTo(2);
        assertThat(usage.getBody().creditsSpent()).isEqualTo(20);
        assertThat(usage.getBody().balance()).isEqualTo(777);
        assertThat(usage.getBody().topWorkflows()).singleElement().satisfies(top -> {
            assertThat(top.runs()).isEqualTo(2);
            assertThat(top.credits()).isEqualTo(20);
            assertThat(top.averageCredits()).isEqualTo(10.0);
        });
    }

    @Test
    void aHundredRowPageWithCreditsOnReadsTheLedgerOnceNotPerRow() {
        UserView m = newUser("hist-statements");
        awaitWallet(m.id());
        WorkflowView w = newWorkflow(m.id(), false, new NewWorkflowStep("only", "echo", null));
        Instant base = Instant.now().minusSeconds(86_400);
        for (int i = 0; i < 150; i++) {
            UUID runId = seedRun(w.id(), m.id(), base.plusSeconds(i), base.plusSeconds(i + 1));
            ledgerRow(m.id(), runId, "debit", 5, base.plusSeconds(i));
        }
        String token = loginAs(m);
        get("/api/v1/runs?size=1", token, PageOf.class);
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        PageOf page = get("/api/v1/runs?size=100", token, PageOf.class).getBody();

        assertThat(page.items()).hasSize(100);
        assertThat(page.total()).isEqualTo(150);
        assertThat(page.items()).allSatisfy(row -> assertThat(row.creditsCharged()).isEqualTo(5));
        // page, count and one grouped ledger read, plus the caller lookup of the request
        assertThat(statistics.getPrepareStatementCount()).isLessThanOrEqualTo(4);
    }

    private void awaitWallet(UUID userId) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (jdbc.queryForObject("SELECT count(*) FROM credit_wallet WHERE user_id = ?", Long.class, userId) == 1) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for the wallet of " + userId, e);
            }
        }
        throw new AssertionError("No wallet was created for " + userId);
    }

    private void ledgerRow(UUID userId, UUID runId, String kind, long amount, Instant at) {
        jdbc.update("INSERT INTO credit_ledger (id, user_id, run_id, kind, amount, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?)", UUID.randomUUID(), userId, runId, kind, amount, Timestamp.from(at));
    }

    private UUID seedRun(UUID workflowId, UUID ownerId, Instant startedAt, Instant finishedAt) {
        UUID runId = UUID.randomUUID();
        jdbc.update("INSERT INTO workflow_run (id, workflow_id, owner_id, status, input, started_at, finished_at) "
                        + "VALUES (?, ?, ?, 'completed', 'seed', ?, ?)",
                runId, workflowId, ownerId, Timestamp.from(startedAt), Timestamp.from(finishedAt));
        return runId;
    }
}
