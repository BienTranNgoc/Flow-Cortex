package com.flowcortex.app.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.credit.api.CreditAccounts;
import com.flowcortex.workflow.api.NewWorkflowStep;
import com.flowcortex.workflow.api.WorkflowStepView;
import com.flowcortex.workflow.api.WorkflowView;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The backend starts with the fetch and ingest step types registered, which the startup pricing
 * coverage check allows only when each has a {@code capability_pricing} row.
 */
@Testcontainers
@SpringBootTest(properties = "flowcortex.credits.enabled=true")
class FetchVideoPricingIT extends AbstractWorkflowModuleIT {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private CreditAccounts creditAccounts;

    @Test
    void aLinkWorkflowIsEstimatedAtTheProbePriceAlone() {
        String probeInput = "{\"outputInputs\":[{\"stepOrder\":1,\"outputName\":\"video\",\"inputKey\":\"media\"}]}";

        WorkflowView saved = newWorkflow(newUser("fetch-estimate").id(), false,
                new NewWorkflowStep("fetch", "fetch_video", null),
                new NewWorkflowStep("probe", "probe", null, probeInput));

        long estimate = creditAccounts.estimate(saved.steps().stream().map(WorkflowStepView::type).toList());
        assertThat(estimate).isPositive().isEqualTo(creditAccounts.estimate(List.of("probe")));
        assertThat(saved.startInput()).isEqualTo("link");
    }

    @Test
    void fetchAndIngestTypesAreFreeWithNoQuantityParams() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT capability, estimator_kind, rate_card_ref, pricing_unit, provider_key,"
                        + " cardinality(quantity_params) AS params"
                        + " FROM capability_pricing"
                        + " WHERE capability IN ('fetch_video', 'ingest_video', 'ingest_image')"
                        + " ORDER BY capability");

        assertThat(rows).extracting(row -> row.get("capability"))
                .containsExactly("fetch_video", "ingest_image", "ingest_video");
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.get("estimator_kind")).isEqualTo("free");
            assertThat(row.get("rate_card_ref")).isNull();
            assertThat(row.get("pricing_unit")).isNull();
            assertThat(row.get("provider_key")).isNull();
            assertThat(row.get("params")).isEqualTo(0);
        });
    }
}
