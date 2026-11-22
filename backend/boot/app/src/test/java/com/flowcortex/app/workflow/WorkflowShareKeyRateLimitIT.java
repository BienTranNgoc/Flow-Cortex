package com.flowcortex.app.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.user.api.UserView;
import com.flowcortex.workflow.api.NewWorkflowStep;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * The anonymous share-key route's per-address budget. A key holder who rotates the user agent gets a new
 * access-log key every request, so the budget, not the dedupe, bounds the rows one address can write.
 * Every client is {@code 127.0.0.1}; the tests tell addresses apart with {@code X-Forwarded-For}, which Tomcat
 * trusts from loopback.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@TestPropertySource(properties = {
        "flowcortex.workflow.share.rate-limit.capacity=3",
        "flowcortex.workflow.share.rate-limit.period=1h"
})
class WorkflowShareKeyRateLimitIT extends AbstractWorkflowHttpIT {

    private static final int BUDGET = 3;

    @Autowired
    private JdbcTemplate jdbc;

    /** The JDK client returns a 429 as is; the default one would sleep through Retry-After. */
    private TestRestTemplate client;

    @BeforeEach
    void noRetryClient() {
        client = new TestRestTemplate(new RestTemplateBuilder()
                .requestFactoryBuilder(ClientHttpRequestFactoryBuilder.jdk())
                .rootUri(restTemplate.getRootUri()));
    }

    @Test
    void anAddressRotatingItsUserAgentIsRefusedAfterItsBudgetAndWritesNoMoreRows() {
        Shared shared = sharedWorkflow("share-limit-rotate");
        String address = nextAddress();

        for (int i = 0; i < BUDGET; i++) {
            assertThat(byKey(shared.key(), address, "agent-" + i, null, null).getStatusCode())
                    .isEqualTo(HttpStatus.OK);
        }
        assertThat(accessLogRows(shared.workflowId())).isEqualTo(BUDGET);

        ResponseEntity<Map> refused = byKey(shared.key(), address, "agent-" + BUDGET, null, null);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(Long.parseLong(refused.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)))
                .isGreaterThanOrEqualTo(1);
        assertThat(refused.getBody()).containsEntry("detail", "Too many attempts to open this share link. Try again later.");
        assertThat(accessLogRows(shared.workflowId())).isEqualTo(BUDGET);
    }

    @Test
    void aDifferentAddressKeepsItsOwnBudget() {
        Shared shared = sharedWorkflow("share-limit-other");
        String spent = nextAddress();
        for (int i = 0; i < BUDGET; i++) {
            byKey(shared.key(), spent, "agent", null, null);
        }
        assertThat(byKey(shared.key(), spent, "agent", null, null).getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        assertThat(byKey(shared.key(), nextAddress(), "agent", null, null).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void theRefusalIsAnsweredInTheRequestLanguage() {
        Shared shared = sharedWorkflow("share-limit-lang");
        String address = nextAddress();
        for (int i = 0; i < BUDGET; i++) {
            byKey(shared.key(), address, "agent", null, null);
        }

        ResponseEntity<Map> refused = byKey(shared.key(), address, "agent", null, "vi");

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(String.valueOf(refused.getBody().get("detail"))).isEqualTo(
                "Bạn đã mở liên kết chia sẻ này quá nhiều lần. Hãy thử lại sau.");
    }

    @Test
    void aSignedInReaderIsNotCountedAgainstTheAnonymousBudget() {
        Shared shared = sharedWorkflow("share-limit-member");
        String address = nextAddress();
        String token = loginAs(newUser("share-limit-reader"));
        for (int i = 0; i < BUDGET + 1; i++) {
            assertThat(byKey(shared.key(), address, "agent", token, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        assertThat(byKey(shared.key(), address, "agent", null, null).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private Shared sharedWorkflow(String prefix) {
        UserView owner = newUser(prefix);
        UUID workflowId = newWorkflow(owner.id(), false, new NewWorkflowStep("only", "echo", null)).id();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(loginAs(owner));
        ResponseEntity<Map> shared = restTemplate.exchange("/api/v1/workflows/" + workflowId + "/visibility",
                HttpMethod.PATCH, new HttpEntity<>(Map.of("visibility", "unlisted"), headers), Map.class);
        assertThat(shared.getStatusCode()).isEqualTo(HttpStatus.OK);
        return new Shared(workflowId, (String) shared.getBody().get("shareKey"));
    }

    private ResponseEntity<Map> byKey(String key, String address, String userAgent, String token, String language) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Forwarded-For", address);
        headers.set(HttpHeaders.USER_AGENT, userAgent);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        if (language != null) {
            headers.set(HttpHeaders.ACCEPT_LANGUAGE, language);
        }
        return client.exchange("/api/v1/workflows/by-key/" + key, HttpMethod.GET, new HttpEntity<>(headers),
                Map.class);
    }

    private int accessLogRows(UUID workflowId) {
        return jdbc.queryForObject("SELECT count(*) FROM workflow_access_log WHERE workflow_id = ?", Integer.class,
                workflowId);
    }

    /** A fresh benchmarking-range address per call, so budgets left in the database by an earlier run never collide. */
    private static String nextAddress() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        return "198.18." + random.nextInt(256) + "." + (1 + random.nextInt(254));
    }

    private record Shared(UUID workflowId, String key) {
    }
}
