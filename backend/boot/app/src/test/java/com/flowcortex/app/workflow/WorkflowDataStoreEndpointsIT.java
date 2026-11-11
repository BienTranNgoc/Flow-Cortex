package com.flowcortex.app.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowcortex.user.api.UserView;
import com.flowcortex.workflow.api.NewWorkflowStep;
import com.flowcortex.workflow.api.ProductVideoWorkflow;
import com.flowcortex.workflow.api.WorkflowView;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/** {@code /api/v1/workflows/{id}/data} against the real, fully-wired app. */
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class WorkflowDataStoreEndpointsIT extends AbstractWorkflowHttpIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper DECIMALS = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void aPutCreatesAnEntryOfTypeJson() throws Exception {
        UserView ana = newUser("ds-create");
        WorkflowView workflow = newWorkflow(ana.id(), false, new NewWorkflowStep("only", "echo", null));

        ResponseEntity<String> response = put(path(workflow, "brand"), loginAs(ana),
                Map.of("value", Map.of("tone", "friendly")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = JSON.readTree(response.getBody());
        assertThat(body.get("key").asText()).isEqualTo("brand");
        assertThat(body.get("value").toString()).isEqualTo("{\"tone\":\"friendly\"}");
        assertThat(body.get("dataType").asText()).isEqualTo("json");
        assertThat(body.get("sizeBytes").asInt()).isEqualTo(19);
    }

    @Test
    void aGetReadsOneEntryWithItsValue() throws Exception {
        UserView ana = newUser("ds-read");
        WorkflowView workflow = newWorkflow(ana.id(), false, new NewWorkflowStep("only", "echo", null));
        String token = loginAs(ana);
        put(path(workflow, "brand"), token, Map.of("value", Map.of("tone", "friendly")));

        ResponseEntity<String> response = get(path(workflow, "brand"), token, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = JSON.readTree(response.getBody());
        assertThat(body.get("value").toString()).isEqualTo("{\"tone\":\"friendly\"}");
        assertThat(body.get("dataType").asText()).isEqualTo("json");
        assertThat(body.get("sizeBytes").asInt()).isEqualTo(19);
    }

    @Test
    void aDecimalWithMoreDigitsThanADoubleRoundTripsOnPutAndGet() throws Exception {
        UserView ana = newUser("ds-decimal");
        WorkflowView workflow = newWorkflow(ana.id(), false, new NewWorkflowStep("only", "echo", null));
        String token = loginAs(ana);

        ResponseEntity<String> put = put(path(workflow, "n"), token, "{\"value\":{\"v\":3.141592653589793238462643}}");
        ResponseEntity<String> get = get(path(workflow, "n"), token, String.class);

        for (ResponseEntity<String> response : List.of(put, get)) {
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(decimal(response, "v")).isEqualTo(new BigDecimal("3.141592653589793238462643"));
        }
    }

    @Test
    void aNumberBeyondTheRangeOfADoubleStaysANumberOnPutAndGet() throws Exception {
        UserView ana = newUser("ds-huge");
        WorkflowView workflow = newWorkflow(ana.id(), false, new NewWorkflowStep("only", "echo", null));
        String token = loginAs(ana);

        ResponseEntity<String> put = put(path(workflow, "n"), token, "{\"value\":{\"v\":1e400,\"w\":-1e-400}}");
        ResponseEntity<String> get = get(path(workflow, "n"), token, String.class);

        for (ResponseEntity<String> response : List.of(put, get)) {
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            JsonNode value = DECIMALS.readTree(response.getBody()).get("value");
            assertThat(value.get("v").isNumber()).isTrue();
            assertThat(value.get("v").decimalValue()).isEqualByComparingTo(new BigDecimal("1e400"));
            assertThat(value.get("w").isNumber()).isTrue();
            assertThat(value.get("w").decimalValue()).isEqualByComparingTo(new BigDecimal("-1e-400"));
        }
    }

    @Test
    void trailingZerosOfADecimalSurvive() throws Exception {
        UserView ana = newUser("ds-zeros");
        WorkflowView workflow = newWorkflow(ana.id(), false, new NewWorkflowStep("only", "echo", null));
        String token = loginAs(ana);

        put(path(workflow, "n"), token, "{\"value\":{\"v\":1.00}}");
        ResponseEntity<String> get = get(path(workflow, "n"), token, String.class);

        assertThat(get.getBody()).contains("\"value\":{\"v\":1.00}");
    }

    @Test
    void aNumberTooLongWhenWrittenOutIsUnprocessableAndStoresNothing() {
        UserView ana = newUser("ds-long-number");
        WorkflowView workflow = newWorkflow(ana.id(), false, new NewWorkflowStep("only", "echo", null));

        ResponseEntity<String> response = put(path(workflow, "n"), loginAs(ana), "{\"value\":[1e5000]}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(response.getBody()).contains("DATA_STORE_NUMBER_TOO_LONG").contains("\"value\"");
        assertThat(count(workflow)).isZero();
    }

    @Test
    void anExponentBeyondAnIntegerIsABadRequestAndStoresNothing() {
        UserView ana = newUser("ds-huge-exponent");
        WorkflowView workflow = newWorkflow(ana.id(), false, new NewWorkflowStep("only", "echo", null));

        ResponseEntity<String> response = put(path(workflow, "n"), loginAs(ana), "{\"value\":1e99999999999}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(count(workflow)).isZero();
    }

    @Test
    void aBodyThatIsNotAJsonObjectWithAValueIsABadRequestAndStoresNothing() {
        UserView ana = newUser("ds-bad-body");
        WorkflowView workflow = newWorkflow(ana.id(), false, new NewWorkflowStep("only", "echo", null));
        String token = loginAs(ana);

        for (String body : List.of("[1]", "{\"value\":", "null", "{\"value\":1,\"dataType\":5}")) {
            ResponseEntity<String> response = put(path(workflow, "n"), token, body);

            assertThat(response.getStatusCode()).as("body %s", body).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        assertThat(count(workflow)).isZero();
    }

    @Test
    void nonAsciiTextRoundTripsWhenTheBodyCarriesNoCharset() throws Exception {
        UserView ana = newUser("ds-utf8");
        WorkflowView workflow = newWorkflow(ana.id(), false, new NewWorkflowStep("only", "echo", null));
        String token = loginAs(ana);

        put(path(workflow, "n"), token, "{\"value\":\"Xin chào, quy trình\"}");
        ResponseEntity<String> get = get(path(workflow, "n"), token, String.class);

        assertThat(JSON.readTree(get.getBody()).get("value").asText()).isEqualTo("Xin chào, quy trình");
    }

    @Test
    void replacingAValueKeepsTheTypeAndTheCreationTime() throws Exception {
        UserView ana = newUser("ds-replace");
        WorkflowView workflow = newWorkflow(ana.id(), false, new NewWorkflowStep("only", "echo", null));
        String token = loginAs(ana);
        JsonNode created = JSON.readTree(put(path(workflow, "brief"), token,
                Map.of("value", Map.of("data", Map.of()), "dataType", "template")).getBody());

        JsonNode replaced = JSON.readTree(put(path(workflow, "brief"), token,
                Map.of("value", Map.of("data", Map.of("x", 1)))).getBody());

        assertThat(replaced.get("value").toString()).isEqualTo("{\"data\":{\"x\":1}}");
        assertThat(replaced.get("dataType").asText()).isEqualTo("template");
        assertThat(replaced.get("createdAt")).isEqualTo(created.get("createdAt"));
    }

    @Test
    void theListIsNewestUpdateFirstWithoutValuesAndCarriesTheLimits() throws Exception {
        UserView ana = newUser("ds-list");
        WorkflowView workflow = newWorkflow(ana.id(), false, new NewWorkflowStep("only", "echo", null));
        String token = loginAs(ana);
        put(path(workflow, "a"), token, Map.of("value", 1));
        put(path(workflow, "b"), token, Map.of("value", 2));

        JsonNode list = JSON.readTree(get(data(workflow), token, String.class).getBody());

        assertThat(list.get("entries")).extracting(entry -> entry.get("key").asText()).containsExactly("b", "a");
        assertThat(list.get("entries").get(0).has("value")).isFalse();
        assertThat(list.get("maxKeys").asInt()).isEqualTo(100);
        assertThat(list.get("maxValueBytes").asInt()).isEqualTo(524288);
    }

    @Test
    void deletingTwiceAnswersNoContentBothTimes() {
        UserView ana = newUser("ds-delete");
        WorkflowView workflow = newWorkflow(ana.id(), false, new NewWorkflowStep("only", "echo", null));
        String token = loginAs(ana);
        put(path(workflow, "a"), token, Map.of("value", 1));

        assertThat(delete(path(workflow, "a"), token).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(delete(path(workflow, "a"), token).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(get(path(workflow, "a"), token, String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void anAbsentKeyIsNotFound() {
        UserView ana = newUser("ds-absent");
        WorkflowView workflow = newWorkflow(ana.id(), false, new NewWorkflowStep("only", "echo", null));

        ResponseEntity<String> response = get(path(workflow, "missing"), loginAs(ana), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("This workflow has no entry under that key");
    }

    @Test
    void aBodyWithoutAValueIsABadRequestAndStoresNothing() {
        UserView ana = newUser("ds-no-value");
        WorkflowView workflow = newWorkflow(ana.id(), false, new NewWorkflowStep("only", "echo", null));
        String token = loginAs(ana);

        ResponseEntity<String> response = put(path(workflow, "k"), token, Map.of("dataType", "json"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(count(workflow)).isZero();
    }

    @Test
    void aBadTypeIsUnprocessableUnderDataType() {
        UserView ana = newUser("ds-bad-type");
        WorkflowView workflow = newWorkflow(ana.id(), false, new NewWorkflowStep("only", "echo", null));

        ResponseEntity<String> response = put(path(workflow, "k"), loginAs(ana),
                Map.of("value", 1, "dataType", "Template"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(response.getBody()).contains("DATA_STORE_TYPE_INVALID").contains("\"dataType\"");
        assertThat(count(workflow)).isZero();
    }

    @Test
    void aMemberCannotReadASharedWorkflowsStore() {
        UserView ana = newUser("ds-shared-owner");
        UserView ben = newUser("ds-shared-reader");
        WorkflowView workflow = newWorkflow(ana.id(), true, new NewWorkflowStep("only", "echo", null));
        put(path(workflow, "brief"), loginAs(ana), Map.of("value", "secret"));
        String benToken = loginAs(ben);

        ResponseEntity<String> list = get(data(workflow), benToken, String.class);
        ResponseEntity<String> one = get(path(workflow, "brief"), benToken, String.class);

        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(one.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(one.getBody()).doesNotContain("secret");
    }

    @Test
    void aMemberCannotWriteAPrivateWorkflowsStore() {
        UserView ana = newUser("ds-private-owner");
        UserView ben = newUser("ds-private-writer");
        WorkflowView workflow = newWorkflow(ana.id(), false, new NewWorkflowStep("only", "echo", null));

        ResponseEntity<String> response = put(path(workflow, "x"), loginAs(ben), Map.of("value", 1));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(count(workflow)).isZero();
    }

    @Test
    void aDeletedWorkflowsStoreIsNotFound() {
        UserView ana = newUser("ds-deleted");
        WorkflowView workflow = newWorkflow(ana.id(), false, new NewWorkflowStep("only", "echo", null));
        workflowService.deleteWorkflow(ana.id(), workflow.id());

        assertThat(get(data(workflow), loginAs(ana), String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void writingTheProductVideoWorkflowsStoreIsAConflict() {
        UserView ana = newUser("ds-product-video");
        String token = loginAs(ana);
        String base = "/api/v1/workflows/" + ProductVideoWorkflow.ID + "/data/k";

        assertThat(put(base, token, Map.of("value", 1)).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(delete(base, token).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void twoNewKeysAtNinetyNineLeaveExactlyOneHundred() throws Exception {
        UserView ana = newUser("ds-quota-race");
        WorkflowView workflow = newWorkflow(ana.id(), false, new NewWorkflowStep("only", "echo", null));
        String token = loginAs(ana);
        for (int i = 0; i < 99; i++) {
            assertThat(put(path(workflow, "k" + i), token, Map.of("value", i)).getStatusCode())
                    .isEqualTo(HttpStatus.OK);
        }

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<ResponseEntity<String>>> answers = new ArrayList<>();
            for (String key : List.of("race-a", "race-b")) {
                answers.add(pool.submit(() -> {
                    start.await();
                    return put(path(workflow, key), token, Map.of("value", key));
                }));
            }
            start.countDown();
            List<HttpStatus> statuses = new ArrayList<>();
            for (Future<ResponseEntity<String>> answer : answers) {
                statuses.add(HttpStatus.valueOf(answer.get().getStatusCode().value()));
            }
            assertThat(statuses).containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.UNPROCESSABLE_CONTENT);
        } finally {
            pool.shutdownNow();
        }
        assertThat(count(workflow)).isEqualTo(100);
    }

    private static BigDecimal decimal(ResponseEntity<String> response, String field) throws Exception {
        return DECIMALS.readTree(response.getBody()).get("value").get(field).decimalValue();
    }

    private long count(WorkflowView workflow) {
        Long rows = jdbcTemplate.queryForObject("SELECT count(*) FROM workflow_data_store WHERE workflow_id = ?",
                Long.class, workflow.id());
        return rows == null ? 0 : rows;
    }

    private static String data(WorkflowView workflow) {
        return "/api/v1/workflows/" + workflow.id() + "/data";
    }

    private static String path(WorkflowView workflow, String key) {
        return data(workflow) + "/" + key;
    }

    private ResponseEntity<String> put(String path, String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(path, HttpMethod.PUT, new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> delete(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return restTemplate.exchange(path, HttpMethod.DELETE, new HttpEntity<>(headers), String.class);
    }
}
