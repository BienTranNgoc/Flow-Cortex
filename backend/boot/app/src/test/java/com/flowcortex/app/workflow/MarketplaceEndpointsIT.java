package com.flowcortex.app.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.user.api.UserView;
import com.flowcortex.workflow.api.NewWorkflowStep;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The marketplace routes over HTTP: routing, who may call them, parameter refusals in both languages and the shape of
 * an item. The ranking rules are covered by the repository IT. Workflows made here are soft-deleted afterwards so the
 * shared catalogue goes back to what it held.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class MarketplaceEndpointsIT extends AbstractWorkflowHttpIT {

    private static final String MARKETPLACE = "/api/v1/marketplace";
    private static final UUID SYSTEM_OWNER = new UUID(0, 0);

    @Autowired
    private JdbcTemplate jdbc;

    private final List<UUID> created = new ArrayList<>();
    private boolean sampleConfigured;

    @AfterEach
    void leaveTheCatalogueAsFound() {
        if (sampleConfigured) {
            jdbc.update("UPDATE onboarding_sample SET workflow_id = NULL, sample_input = NULL, source_locale = NULL"
                    + " WHERE id = 1");
            sampleConfigured = false;
        }
        for (UUID id : created) {
            jdbc.update("UPDATE workflow SET deleted_at = now() WHERE id = ?", id);
            jdbc.update("DELETE FROM workflow_discovery_stats WHERE workflow_id = ?", id);
            jdbc.update("DELETE FROM workflow_rating_total WHERE workflow_id = ?", id);
        }
        created.clear();
    }

    @Test
    void firstPageOfTwentyThreeIsTwentyWithTotal() {
        UserView ana = newUser("mk-first-ana");
        UserView ben = member(ana, "mk-first-ben");
        String token = loginAs(ben);
        long before = ((Number) call(MARKETPLACE + "/stats", token, null).getBody().get("totalWorkflows"))
                .longValue();
        for (int i = 0; i < 23; i++) {
            publicWorkflow(ana);
        }

        Map<?, ?> page = call(MARKETPLACE + "/workflows", token, null).getBody();
        Map<?, ?> next = call(MARKETPLACE + "/workflows?page=1&size=20", token, null).getBody();

        assertThat(items(page)).hasSize(20);
        assertThat(page.get("meta")).isEqualTo(Map.of("total", (int) before + 23, "page", 0, "size", 20));
        assertThat(items(next)).hasSize((int) Math.min(20, before + 3));
        assertThat(call(MARKETPLACE + "/stats", token, null).getBody().get("totalWorkflows"))
                .isEqualTo((int) before + 23);
    }

    @Test
    void everyRouteIs401WithoutToken() {
        UUID anyId = UUID.randomUUID();
        for (String path : List.of(MARKETPLACE + "/workflows", MARKETPLACE + "/recommended",
                MARKETPLACE + "/workflows/" + anyId + "/similar", MARKETPLACE + "/facets", MARKETPLACE + "/stats",
                "/api/v1/workflows/counts")) {
            assertThat(call(path, null, null).getStatusCode()).as(path).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    @Test
    void badParamIs400NamingItInBothLanguages() {
        UserView ana = newUser("mk-bad-ana");
        String token = loginAs(ana);
        UUID workflow = publicWorkflow(ana);
        String nineTerms = "a+b+c+d+e+f+g+h+i";
        String similar = MARKETPLACE + "/workflows/" + workflow + "/similar";
        List<String[]> refusals = List.of(
                new String[] {MARKETPLACE + "/workflows?size=51", "size", "must be less than or equal to 50",
                        "phải nhỏ hơn hoặc bằng 50"},
                new String[] {MARKETPLACE + "/workflows?size=0", "size", "must be greater than or equal to 1",
                        "phải lớn hơn hoặc bằng 1"},
                new String[] {MARKETPLACE + "/workflows?sort=hot", "sort",
                        "must be one of trending, popular, newest, topRated or name",
                        "phải là một trong các giá trị trending, popular, newest, topRated hoặc name"},
                new String[] {MARKETPLACE + "/workflows?sort=", "sort",
                        "must be one of trending, popular, newest, topRated or name",
                        "phải là một trong các giá trị trending, popular, newest, topRated hoặc name"},
                new String[] {MARKETPLACE + "/workflows?stepType=teleport", "stepType",
                        "must be at most 10 distinct step types, each one the engine knows",
                        "tối đa 10 loại bước khác nhau, mỗi loại phải là loại bước hệ thống hỗ trợ"},
                new String[] {MARKETPLACE + "/workflows?q=" + nineTerms, "q",
                        "must be at most 100 characters and 8 words", "không được quá 100 ký tự và 8 từ"},
                new String[] {MARKETPLACE + "/workflows?q=" + "x".repeat(101), "q",
                        "must be at most 100 characters and 8 words", "không được quá 100 ký tự và 8 từ"},
                new String[] {MARKETPLACE + "/recommended?limit=21", "limit", "must be less than or equal to 20",
                        "phải nhỏ hơn hoặc bằng 20"},
                new String[] {similar + "?limit=0", "limit", "must be greater than or equal to 1",
                        "phải lớn hơn hoặc bằng 1"});

        for (String[] refusal : refusals) {
            assertRefused(refusal[0], token, null, refusal[1], refusal[2]);
            assertRefused(refusal[0], token, "vi", refusal[1], refusal[3]);
        }
        for (String path : List.of(MARKETPLACE + "/workflows?page=abc", MARKETPLACE + "/workflows?page=-1",
                MARKETPLACE + "/recommended?limit=abc", similar + "?limit=abc")) {
            String param = path.substring(path.lastIndexOf('?') + 1, path.indexOf('=', path.lastIndexOf('?')));
            Map<String, ?> english = refusedErrors(path, token, null);
            Map<String, ?> vietnamese = refusedErrors(path, token, "vi");
            assertThat(english).as(path).containsKey(param);
            assertThat(vietnamese.get(param)).as(path).isNotEqualTo(english.get(param));
        }
    }

    @Test
    void itemCarriesRatingMembersAndBookmarkedButNoAuthorId() {
        UserView ana = newUser("mk-item-ana");
        UserView ben = member(ana, "mk-item-ben");
        UUID workflow = publicWorkflow(ana);
        UUID unrated = publicWorkflow(ana);
        jdbc.update("INSERT INTO workflow_rating_total (workflow_id, rating_count, rating_sum) VALUES (?, 3, 13)",
                workflow);
        jdbc.update("INSERT INTO workflow_discovery_stats (workflow_id, runners_7d, saves_7d, members_30d,"
                + " trending_score, computed_at) VALUES (?, 1, 1, 2, 8, now())", workflow);
        String token = loginAs(ben);
        call("/api/v1/workflows/" + workflow + "/bookmark", HttpMethod.PUT, token, Map.of("bookmarked", true));

        ResponseEntity<Map> response = call(MARKETPLACE + "/workflows?q=" + nameOf(workflow), token, null);
        Map<String, Object> item = items(response.getBody()).getFirst();
        Map<String, Object> bare = items(call(MARKETPLACE + "/workflows?q=" + nameOf(unrated), token, null).getBody())
                .getFirst();

        assertThat(response.getBody().get("data")).asList().hasSize(1);
        assertThat(((Map<?, ?>) item.get("workflow")).get("id")).isEqualTo(workflow.toString());
        assertThat(item).containsOnlyKeys("workflow", "authorName", "rating", "membersLast30Days", "bookmarked");
        assertThat(item.get("authorName")).isEqualTo(ana.name());
        assertThat(((Map<?, ?>) item.get("rating")).get("count")).isEqualTo(3);
        assertThat(new BigDecimal(String.valueOf(((Map<?, ?>) item.get("rating")).get("average"))))
                .isEqualByComparingTo("4.33");
        assertThat(item.get("membersLast30Days")).isEqualTo(2);
        assertThat(item.get("bookmarked")).isEqualTo(true);
        assertThat(response.getBody().toString()).doesNotContain(ana.id().toString()).doesNotContain(ana.email());
        assertThat(((Map<?, ?>) bare.get("rating")).get("average")).isNull();
        assertThat(((Map<?, ?>) bare.get("rating")).get("count")).isEqualTo(0);
        assertThat(bare.get("bookmarked")).isEqualTo(false);
    }

    @Test
    void systemOwnedItemHasNullAuthor() {
        UserView ana = newUser("mk-system-ana");
        String token = loginAs(ana);
        UUID workflow = UUID.randomUUID();
        jdbc.update("INSERT INTO workflow (id, owner_id, name, is_shared, created_at, updated_at, status,"
                        + " visibility) VALUES (?, ?, ?, true, now(), now(), 'published', 'public')",
                workflow, SYSTEM_OWNER, "seeded-" + workflow);
        jdbc.update("INSERT INTO workflow_step (id, workflow_id, step_order, name, type) VALUES (?, ?, 1, 's', 'echo')",
                UUID.randomUUID(), workflow);
        created.add(workflow);

        Map<String, Object> item = items(call(MARKETPLACE + "/workflows?q=" + workflow, token, null).getBody())
                .getFirst();

        assertThat(((Map<?, ?>) item.get("workflow")).get("id")).isEqualTo(workflow.toString());
        assertThat(item.get("authorName")).isNull();
    }

    @Test
    void similarOfInvisibleWorkflowIs404() {
        UserView ana = newUser("mk-similar-ana");
        UserView ben = member(ana, "mk-similar-ben");
        UUID hidden = newWorkflow(ana.id(), false, new NewWorkflowStep("only", "echo", null)).id();
        created.add(hidden);
        UUID visible = publicWorkflow(ana);
        String token = loginAs(ben);

        ResponseEntity<Map> refused = call(MARKETPLACE + "/workflows/" + hidden + "/similar", token, null);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(MARKETPLACE + "/workflows/" + UUID.randomUUID() + "/similar", token, null).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        ResponseEntity<Map> found = call(MARKETPLACE + "/workflows/" + visible + "/similar", token, null);
        assertThat(found.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(items(found.getBody())).isEmpty();
    }

    @Test
    void countsRouteIsNotAWorkflowId() {
        UserView ana = newUser("mk-counts-ana");
        UserView ben = member(ana, "mk-counts-ben");
        String token = loginAs(ben);
        newWorkflow(ben.id(), true, new NewWorkflowStep("only", "echo", null));
        UUID draft = UUID.randomUUID();
        jdbc.update("INSERT INTO workflow (id, owner_id, name, is_shared, created_at, updated_at, status,"
                + " visibility) VALUES (?, ?, ?, false, now(), now(), 'draft', 'private')", draft, ben.id(),
                "draft-" + draft);
        UUID deleted = UUID.randomUUID();
        jdbc.update("INSERT INTO workflow (id, owner_id, name, is_shared, created_at, updated_at, status,"
                + " visibility, deleted_at) VALUES (?, ?, ?, true, now(), now(), 'published', 'public', now())",
                deleted, ben.id(), "gone-" + deleted);
        UUID first = publicWorkflow(ana);
        UUID second = publicWorkflow(ana);
        UUID wentPrivate = publicWorkflow(ana);
        for (UUID saved : List.of(first, second, wentPrivate)) {
            call("/api/v1/workflows/" + saved + "/bookmark", HttpMethod.PUT, token, Map.of("bookmarked", true));
        }
        jdbc.update("UPDATE workflow SET visibility = 'private', is_shared = false WHERE id = ?", wentPrivate);
        created.addAll(List.of(draft, deleted));

        ResponseEntity<Map> counts = call("/api/v1/workflows/counts", token, null);
        Map<?, ?> saved = call("/api/v1/workflows/bookmarked", token, null).getBody();

        assertThat(counts.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(counts.getBody()).isEqualTo(Map.of("mine", 2, "saved", 2));
        assertThat(((Map<?, ?>) saved.get("meta")).get("total")).isEqualTo(2);
    }

    @Test
    void onboardingSampleIsLeftOutOfSimilarAndRecommended() {
        UserView ana = newUser("mk-sample-ana");
        UserView ben = member(ana, "mk-sample-ben");
        String token = loginAs(ben);
        UUID sample = publicWorkflow(ana);
        UUID other = publicWorkflow(ana);
        for (UUID workflow : List.of(sample, other)) {
            jdbc.update("INSERT INTO workflow_discovery_stats (workflow_id, runners_7d, saves_7d, members_30d,"
                    + " trending_score, computed_at) VALUES (?, 1, 0, 5, 3, now())", workflow);
        }
        jdbc.update("UPDATE onboarding_sample SET workflow_id = ?, sample_input = 'hello', source_locale = 'en'"
                + " WHERE id = 1", sample);
        sampleConfigured = true;

        List<String> recommended = items(call(MARKETPLACE + "/recommended?limit=20", token, null).getBody()).stream()
                .map(item -> (String) ((Map<?, ?>) item.get("workflow")).get("id")).toList();

        assertThat(recommended).contains(other.toString()).doesNotContain(sample.toString());
        assertThat(items(call(MARKETPLACE + "/workflows/" + sample + "/similar", token, null).getBody())).isEmpty();
        assertThat(items(call(MARKETPLACE + "/workflows?q=" + nameOf(sample), token, null).getBody())).hasSize(1);
    }

    // ---- support ----

    private UUID publicWorkflow(UserView owner) {
        UUID id = newWorkflow(owner.id(), true, new NewWorkflowStep("only", "echo", null)).id();
        created.add(id);
        return id;
    }

    private String nameOf(UUID workflow) {
        return jdbc.queryForObject("SELECT name FROM workflow WHERE id = ?", String.class, workflow);
    }

    private UserView member(UserView admin, String prefix) {
        return userService.createUser(admin.id(), uniqueEmail(prefix), "password123", prefix, "MEMBER");
    }

    private void assertRefused(String path, String token, String language, String param, String message) {
        Map<String, ?> errors = refusedErrors(path, token, language);
        assertThat(errors).as(path + " " + language).containsOnlyKeys(param);
        assertThat(errors.get(param)).as(path + " " + language).isEqualTo(message);
    }

    private Map<String, ?> refusedErrors(String path, String token, String language) {
        ResponseEntity<Map> response = call(path, token, language);
        assertThat(response.getStatusCode()).as(path).isEqualTo(HttpStatus.BAD_REQUEST);
        return (Map<String, ?>) response.getBody().get("errors");
    }

    private ResponseEntity<Map> call(String path, String token, String language) {
        HttpHeaders headers = bearer(token);
        if (language != null) {
            headers.set(HttpHeaders.ACCEPT_LANGUAGE, language);
        }
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(null, headers), Map.class);
    }

    private ResponseEntity<Map> call(String path, HttpMethod method, String token, Object body) {
        return restTemplate.exchange(path, method, new HttpEntity<>(body, bearer(token)), Map.class);
    }

    private static HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return headers;
    }

    private static List<Map<String, Object>> items(Map<?, ?> body) {
        return (List<Map<String, Object>>) body.get("data");
    }
}
