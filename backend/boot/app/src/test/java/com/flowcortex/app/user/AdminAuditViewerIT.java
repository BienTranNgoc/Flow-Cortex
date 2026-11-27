package com.flowcortex.app.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import com.flowcortex.user.api.UserView;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.invocation.Invocation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterUtils;
import org.springframework.jdbc.core.namedparam.ParsedSql;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.web.util.UriComponentsBuilder;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The audit log viewer over real HTTP and real rows: list, filters, validation, facets, the recorded
 * export and the query plans. The table is emptied before each case; rows are inserted directly so
 * every case controls the time, actor and label of what it reads.
 */
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class AdminAuditViewerIT extends AbstractAuditSpyIT {

    private static final String PASSWORD = "correct-password-1";
    private static final String BASE = "/api/v1/admin/audit-log";
    private static final String BOM = "﻿";
    private static final Instant NINE = Instant.parse("2026-11-15T09:00:00Z");
    private static final Instant TEN = Instant.parse("2026-11-15T10:00:00Z");
    private static final Instant ELEVEN = Instant.parse("2026-11-15T11:00:00Z");
    private static final UUID MAI = UUID.fromString("00000000-0000-4000-8000-0000000000a1");
    private static final UUID LAN = UUID.fromString("00000000-0000-4000-8000-0000000000a2");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private JdbcTemplate jdbc;

    private UserView admin;
    private String adminToken;
    private String managerToken;
    private String supportToken;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM admin_audit_event");
        admin = userService.createInitialAdmin(uniqueEmail("audit-admin"), PASSWORD, "Audit Admin");
        adminToken = login(admin.email(), PASSWORD);
        managerToken = login(staff("audit-manager", "OPERATION_MANAGER").email(), PASSWORD);
        supportToken = login(staff("audit-support", "CUSTOMER_SUPPORT").email(), PASSWORD);
    }

    private UserView staff(String label, String role) {
        return userService.createUser(admin.id(), uniqueEmail(label), PASSWORD, label, role);
    }

    /** One audit row; the defaults describe a successful {@code user.update} by Mai about no resource label. */
    private static final class Seed {
        private Instant at = NINE;
        private UUID actor = MAI;
        private String actorEmail = "mai.tran@example.com";
        private String actorName = "Mai Tran";
        private String action = "user.update";
        private String type = "support_kb_article";
        private String resourceId;
        private String label;
        private String status = "success";
        private String errorCode;
        private String summary;
        private String ip;
        private String userAgent;
        private UUID id = UUID.randomUUID();

        Seed at(Instant value) {
            at = value;
            return this;
        }

        Seed id(String value) {
            id = UUID.fromString(value);
            return this;
        }

        Seed actor(UUID value, String email, String name) {
            actor = value;
            actorEmail = email;
            actorName = name;
            return this;
        }

        Seed action(String value) {
            action = value;
            return this;
        }

        Seed about(String resourceType, String resourceIdValue, String labelValue) {
            type = resourceType;
            resourceId = resourceIdValue;
            label = labelValue;
            return this;
        }

        Seed failed(String code) {
            status = "failed";
            errorCode = code;
            return this;
        }

        Seed summary(String json) {
            summary = json;
            return this;
        }

        Seed origin(String address, String agent) {
            ip = address;
            userAgent = agent;
            return this;
        }
    }

    private UUID insert(Seed row) {
        jdbc.update("""
                INSERT INTO admin_audit_event (id, actor_user_id, actor_email, actor_name, action, resource_type,
                    resource_id, resource_label, change_summary, status, error_code, ip, user_agent, origin_source,
                    occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?, 'http', ?)
                """, row.id, row.actor, row.actorEmail, row.actorName, row.action, row.type, row.resourceId,
                row.label, row.summary, row.status, row.errorCode, row.ip, row.userAgent, Timestamp.from(row.at));
        return row.id;
    }

    private static URI uri(String path, String... nameValues) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromPath(path);
        Map<String, String> values = new HashMap<>();
        for (int i = 0; i < nameValues.length; i += 2) {
            builder.queryParam(nameValues[i], "{" + nameValues[i] + "}");
            values.put(nameValues[i], nameValues[i + 1]);
        }
        return builder.encode().buildAndExpand(values).toUri();
    }

    private <T> ResponseEntity<T> call(String token, Class<T> type, String path, String... nameValues) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON));
        return restTemplate.exchange(URI.create(restTemplate.getRootUri() + uri(path, nameValues)), HttpMethod.GET,
                new HttpEntity<>(headers), type);
    }

    private JsonNode list(String token, String... nameValues) {
        ResponseEntity<String> response = call(token, String.class, BASE, nameValues);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return JSON.readTree(response.getBody());
    }

    private ResponseEntity<byte[]> export(String token, String... nameValues) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setAccept(List.of(MediaType.parseMediaType("text/csv"), MediaType.APPLICATION_PROBLEM_JSON));
        headers.set(HttpHeaders.USER_AGENT, "AdminAuditViewerIT/1.0");
        return restTemplate.exchange(URI.create(restTemplate.getRootUri() + uri(BASE + "/export", nameValues)),
                HttpMethod.GET, new HttpEntity<>(headers), byte[].class);
    }

    private static String text(ResponseEntity<byte[]> response) {
        return new String(response.getBody(), StandardCharsets.UTF_8);
    }

    private static List<String> ids(JsonNode page) {
        List<String> ids = new ArrayList<>();
        page.path("items").forEach(item -> ids.add(item.path("id").asString()));
        return ids;
    }

    private long exportRows() {
        return jdbc.queryForObject("SELECT count(*) FROM admin_audit_event WHERE action = 'audit_log.export'",
                Long.class);
    }

    @Test
    void newestFirstAcrossPages() {
        List<UUID> inserted = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            inserted.add(insert(new Seed().at(NINE.plusSeconds(i))));
        }

        JsonNode first = list(adminToken, "size", "50");
        JsonNode second = list(adminToken, "size", "50", "cursor", first.path("nextCursor").asString());
        JsonNode third = list(adminToken, "size", "50", "cursor", second.path("nextCursor").asString());

        assertThat(first.path("items")).hasSize(50);
        assertThat(second.path("items")).hasSize(50);
        assertThat(third.path("items")).hasSize(20);
        assertThat(third.path("nextCursor").isNull()).isTrue();
        List<String> all = new ArrayList<>(ids(first));
        all.addAll(ids(second));
        all.addAll(ids(third));
        List<String> newestFirst = new ArrayList<>(inserted.stream().map(UUID::toString).toList());
        Collections.reverse(newestFirst);
        assertThat(all).containsExactlyElementsOf(newestFirst);
    }

    @Test
    void defaultPageHoldsFiftyRows() {
        for (int i = 0; i < 51; i++) {
            insert(new Seed().at(NINE.plusSeconds(i)));
        }

        JsonNode page = list(managerToken);

        assertThat(page.path("items")).hasSize(50);
        assertThat(page.path("nextCursor").asString()).isNotBlank();
    }

    @Test
    void rowsSharingATime() {
        insert(new Seed().at(TEN).id("00000000-0000-4000-8000-00000000000a"));
        insert(new Seed().at(TEN).id("00000000-0000-4000-8000-00000000000b"));
        insert(new Seed().at(TEN).id("00000000-0000-4000-8000-00000000000c"));

        JsonNode first = list(adminToken, "size", "2");
        JsonNode second = list(adminToken, "size", "2", "cursor", first.path("nextCursor").asString());

        assertThat(ids(first)).containsExactly("00000000-0000-4000-8000-00000000000c",
                "00000000-0000-4000-8000-00000000000b");
        assertThat(ids(second)).containsExactly("00000000-0000-4000-8000-00000000000a");
        assertThat(second.path("nextCursor").isNull()).isTrue();
    }

    @Test
    void rowsAMicrosecondApartKeepTheirOrderAcrossPages() {
        Instant base = Instant.parse("2026-11-15T10:00:00.123456Z");
        UUID older = insert(new Seed().at(base));
        UUID newer = insert(new Seed().at(base.plusNanos(1_000)));

        JsonNode first = list(adminToken, "size", "1");
        JsonNode second = list(adminToken, "size", "1", "cursor", first.path("nextCursor").asString());

        assertThat(ids(first)).containsExactly(newer.toString());
        assertThat(ids(second)).containsExactly(older.toString());
    }

    @Test
    void callerWithoutTheKey() {
        insert(new Seed());

        ResponseEntity<String> list = call(supportToken, String.class, BASE);
        ResponseEntity<String> facets = call(supportToken, String.class, BASE + "/facets");

        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(list.getBody()).doesNotContain("mai.tran@example.com");
        assertThat(facets.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void rowFields() {
        insert(new Seed().at(Instant.parse("2026-11-15T09:00:00.123456Z")).action("user.update")
                .about("admin_account", "acc-1", null).failed("user.lastSuperAdmin")
                .summary("{\"role\":\"MEMBER\"}").origin("203.0.113.7", "Mozilla/5.0"));

        JsonNode item = list(adminToken).path("items").get(0);

        assertThat(item.path("action").asString()).isEqualTo("user.update");
        assertThat(item.path("status").asString()).isEqualTo("failed");
        assertThat(item.path("errorCode").asString()).isEqualTo("user.lastSuperAdmin");
        assertThat(item.path("changeSummary")).isEqualTo(JSON.readTree("{\"role\":\"MEMBER\"}"));
        assertThat(item.path("origin").asString()).isEqualTo("http");
        assertThat(item.path("ip").asString()).isEqualTo("203.0.113.7");
        assertThat(item.path("userAgent").asString()).isEqualTo("Mozilla/5.0");
        assertThat(item.path("actorUserId").asString()).isEqualTo(MAI.toString());
        assertThat(item.path("actorEmail").asString()).isEqualTo("mai.tran@example.com");
        assertThat(item.path("actorName").asString()).isEqualTo("Mai Tran");
        assertThat(item.path("resourceType").asString()).isEqualTo("admin_account");
        assertThat(item.path("resourceId").asString()).isEqualTo("acc-1");
        assertThat(item.path("resourceLabel").isNull()).isTrue();
        assertThat(Instant.parse(item.path("occurredAt").asString()))
                .isEqualTo(Instant.parse("2026-11-15T09:00:00.123456Z"));
    }

    @Test
    void combinedFilters() {
        insert(new Seed().at(NINE).action("user.update"));
        UUID failed = insert(new Seed().at(TEN).action("user.update").failed("user.notFound"));
        insert(new Seed().at(TEN.plusSeconds(1800)).actor(LAN, "lan@example.com", "Lan").action("user.delete"));

        JsonNode page = list(adminToken, "action", "user.update", "status", "failed");

        assertThat(ids(page)).containsExactly(failed.toString());
    }

    @Test
    void timeRangeBounds() {
        insert(new Seed().at(NINE));
        UUID inside = insert(new Seed().at(TEN));
        insert(new Seed().at(ELEVEN));

        JsonNode page = list(adminToken, "from", "2026-11-15T10:00:00Z", "to", "2026-11-15T11:00:00Z");

        assertThat(ids(page)).containsExactly(inside.toString());
    }

    @Test
    void searchText() {
        UUID byMai = insert(new Seed().about("support_kb_article", "kb-1", "Other title"));
        UUID byLabel = insert(new Seed().actor(LAN, "lan@example.com", "Lan")
                .about("support_kb_article", "kb-2", "Đổi mật khẩu"));

        assertThat(ids(list(adminToken, "q", "MAI.TRAN"))).containsExactly(byMai.toString());
        assertThat(ids(list(adminToken, "q", "mật khẩu"))).containsExactly(byLabel.toString());
        assertThat(ids(list(adminToken, "q", "KB-2"))).containsExactly(byLabel.toString());
    }

    @Test
    void searchWildcardCharactersAreLiteral() {
        UUID literal = insert(new Seed().about("support_kb_article", "50%", null));
        insert(new Seed().about("support_kb_article", "500", null));
        UUID underscore = insert(new Seed().about("support_kb_article", "a_b-1", null));
        insert(new Seed().about("support_kb_article", "axb-1", null));

        assertThat(ids(list(adminToken, "q", "50%"))).containsExactly(literal.toString());
        assertThat(ids(list(adminToken, "q", "a_b"))).containsExactly(underscore.toString());
    }

    @Test
    void oneResourcesHistory() {
        UUID newer = insert(new Seed().at(TEN).action("support_kb_article.update")
                .about("support_kb_article", "kb-7", "Guide"));
        UUID older = insert(new Seed().at(NINE).action("support_kb_article.create")
                .about("support_kb_article", "kb-7", "Guide"));
        insert(new Seed().at(ELEVEN).about("support_kb_article", "kb-8", "Other"));
        insert(new Seed().at(ELEVEN).about("workflow", "kb-7", "Same id, other type"));

        JsonNode page = list(adminToken, "resourceType", "support_kb_article", "resourceId", "kb-7");

        assertThat(ids(page)).containsExactly(newer.toString(), older.toString());
    }

    @Test
    void filterByActor() {
        UUID byMai = insert(new Seed().at(NINE));
        insert(new Seed().at(TEN).actor(LAN, "lan@example.com", "Lan"));

        assertThat(ids(list(managerToken, "actorUserId", MAI.toString()))).containsExactly(byMai.toString());
    }

    @Test
    void auditRowAboutAMember() {
        insert(new Seed().action("user.update").about("user", UUID.randomUUID().toString(), "Lê Thị Lan"));

        JsonNode item = list(managerToken).path("items").get(0);

        assertThat(item.path("resourceLabel").asString()).isEqualTo("L*** T*** L***");
    }

    @Test
    void auditRowAboutAMemberLabelledWithAnEmail() {
        insert(new Seed().action("user.create").about("user", UUID.randomUUID().toString(), "lan.le@example.com"));

        JsonNode item = list(managerToken).path("items").get(0);

        assertThat(item.path("resourceLabel").asString()).isEqualTo("l****e@example.com");
    }

    @Test
    void staffAndOtherResourcesAreNotMasked() {
        insert(new Seed().at(NINE).action("admin_account.update").about("admin_account", "acc-1", "Mai Tran"));
        insert(new Seed().at(TEN).action("support_kb_article.update")
                .about("support_kb_article", "kb-1", "Đổi mật khẩu"));

        JsonNode items = list(managerToken).path("items");

        assertThat(items.get(0).path("resourceLabel").asString()).isEqualTo("Đổi mật khẩu");
        assertThat(items.get(1).path("resourceLabel").asString()).isEqualTo("Mai Tran");
        assertThat(items.get(0).path("actorEmail").asString()).isEqualTo("mai.tran@example.com");
        assertThat(items.get(0).path("actorName").asString()).isEqualTo("Mai Tran");
    }

    @Test
    void invalidParametersAreRefusedWithNoRows() {
        insert(new Seed());

        List<String[]> bad = List.of(
                new String[] {"q", "ab"},
                new String[] {"q", "a".repeat(101)},
                new String[] {"status", "error"},
                new String[] {"cursor", "not-a-cursor"},
                new String[] {"cursor", cursorWithAnIdThatIsNoUuid()},
                new String[] {"size", "0"},
                new String[] {"size", "101"},
                new String[] {"actorUserId", "not-a-uuid"},
                new String[] {"from", "yesterday"},
                new String[] {"from", "2026-11-15T11:00:00Z", "to", "2026-11-15T10:00:00Z"});

        for (String[] params : bad) {
            ResponseEntity<String> response = call(adminToken, String.class, BASE, params);
            assertThat(response.getStatusCode()).as(String.join("=", params)).isEqualTo(HttpStatus.BAD_REQUEST);
            JsonNode problem = JSON.readTree(response.getBody());
            assertThat(problem.path("type").asString()).endsWith("validation-failed");
            assertThat(problem.path("items").isMissingNode()).isTrue();
        }
    }

    @Test
    void validationErrorsNameTheParameter() {
        ResponseEntity<String> response = call(adminToken, String.class, BASE, "q", "ab", "status", "error");

        JsonNode errors = JSON.readTree(response.getBody()).path("errors");
        assertThat(errors.path("q").asString()).isEqualTo("Search text must be 3 to 100 characters");
        assertThat(errors.path("status").asString()).isEqualTo("Status must be success or failed");
    }

    @Test
    void reversedRangeOnExport() {
        insert(new Seed());

        ResponseEntity<byte[]> response = export(adminToken, "from", "2026-11-15T11:00:00Z", "to",
                "2026-11-15T10:00:00Z");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getHeaders().containsHeader(HttpHeaders.CONTENT_DISPOSITION)).isFalse();
        assertThat(text(response)).contains("validation-failed");
        assertThat(exportRows()).isZero();
    }

    @Test
    void facetsOfTheRunningApplication() {
        ResponseEntity<String> response = call(managerToken, String.class, BASE + "/facets");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode facets = JSON.readTree(response.getBody());
        List<String> actions = new ArrayList<>();
        facets.path("actions").forEach(node -> actions.add(node.asString()));
        List<String> types = new ArrayList<>();
        facets.path("resourceTypes").forEach(node -> types.add(node.asString()));
        assertThat(actions).contains("user.update", "support_kb_article.create", "user.reveal_identity",
                "user.export", "audit_log.export").isSorted().doesNotHaveDuplicates();
        assertThat(types).contains("user", "support_kb_article", "audit_log").isSorted().doesNotHaveDuplicates();
        assertThat(types).allMatch(type -> actions.stream().anyMatch(action -> action.startsWith(type + ".")));
    }

    @Test
    void filteredExport() {
        for (int i = 0; i < 3; i++) {
            insert(new Seed().at(NINE.plusSeconds(i)).failed("user.notFound"));
        }
        for (int i = 0; i < 5; i++) {
            insert(new Seed().at(TEN.plusSeconds(i)));
        }

        ResponseEntity<byte[]> response = export(adminToken, "status", "failed");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType().toString()).startsWith("text/csv")
                .containsIgnoringCase("charset=utf-8");
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .matches("attachment; filename=\"audit-log-\\d{8}-\\d{4}\\.csv\"");
        String body = text(response);
        assertThat(body).startsWith(BOM + "Time,Actor ID,Actor email,Actor name,Action,Resource type,Resource ID,"
                + "Resource label,Status,Error code,IP,User agent,Origin,Change summary\r\n");
        String[] lines = body.split("\r\n");
        assertThat(lines).hasSize(4);
        assertThat(lines[1]).startsWith("\"2026-11-15T09:00:02Z\"");
        assertThat(lines[3]).startsWith("\"2026-11-15T09:00:00Z\"");
        assertThat(body).doesNotContain("\"success\"");
    }

    @Test
    void changeSummaryCell() {
        insert(new Seed().action("user.create").summary("{\"role\":\"CREATOR\"}"));

        String body = text(export(adminToken));

        assertThat(body).contains("\"{\"\"role\"\":\"\"CREATOR\"\"}\"");
    }

    @Test
    void formulaInALabel() {
        insert(new Seed().about("support_kb_article", "kb-1", "=HYPERLINK(\"http://x\",\"y\")"));

        String body = text(export(adminToken));

        assertThat(body).contains("\"'=HYPERLINK(\"\"http://x\"\",\"\"y\"\")\"");
    }

    @Test
    void viewerWithoutTheExportKey() {
        insert(new Seed());

        ResponseEntity<byte[]> response = export(managerToken);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getHeaders().containsHeader(HttpHeaders.CONTENT_DISPOSITION)).isFalse();
        assertThat(exportRows()).isZero();
    }

    @Test
    void overTheLimit() {
        for (int i = 0; i < 6; i++) {
            insert(new Seed().at(NINE.plusSeconds(i)));
        }

        ResponseEntity<byte[]> response = export(adminToken);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(response.getHeaders().getContentType().toString()).startsWith("application/problem+json");
        assertThat(response.getHeaders().containsHeader(HttpHeaders.CONTENT_DISPOSITION)).isFalse();
        JsonNode problem = JSON.readTree(text(response));
        assertThat(problem.path("code").asString()).isEqualTo("audit.exportTooLarge");
        assertThat(problem.path("detail").asString()).contains("6").contains("5");
    }

    @Test
    void atTheLimit() {
        for (int i = 0; i < 5; i++) {
            insert(new Seed().at(NINE.plusSeconds(i)));
        }

        ResponseEntity<byte[]> response = export(adminToken);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(text(response).split("\r\n")).hasSize(6);
    }

    @Test
    void refusedExportLeavesNoRow() {
        for (int i = 0; i < 6; i++) {
            insert(new Seed().at(NINE.plusSeconds(i)));
        }

        export(adminToken);

        assertThat(exportRows()).isZero();
    }

    @Test
    void exportRecorded() {
        for (int i = 0; i < 3; i++) {
            insert(new Seed().at(NINE.plusSeconds(i)).failed("user.notFound"));
        }
        insert(new Seed().at(TEN));

        ResponseEntity<byte[]> response = export(adminToken, "status", "failed");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(text(response).split("\r\n")).hasSize(4);
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT *, change_summary::text AS summary_text FROM admin_audit_event
                WHERE action = 'audit_log.export'""");
        assertThat(row.get("resource_type")).isEqualTo("audit_log");
        assertThat(row.get("actor_user_id")).isEqualTo(admin.id());
        assertThat(row.get("status")).isEqualTo("success");
        assertThat(row.get("user_agent")).isEqualTo("AdminAuditViewerIT/1.0");
        assertThat(row.get("ip")).isNotNull();
        assertThat(JSON.readTree((String) row.get("summary_text")))
                .isEqualTo(JSON.readTree("{\"status\":\"failed\",\"rowCount\":3}"));
    }

    @Test
    void unfilteredExportHoldsExactlyRowCount() {
        for (int i = 0; i < 5; i++) {
            insert(new Seed().at(NINE.plusSeconds(i)));
        }

        ResponseEntity<byte[]> response = export(adminToken);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = text(response);
        assertThat(body.split("\r\n")).hasSize(6);
        assertThat(body).doesNotContain("audit_log.export");
        String summary = jdbc.queryForObject(
                "SELECT change_summary::text FROM admin_audit_event WHERE action = 'audit_log.export'", String.class);
        assertThat(JSON.readTree(summary)).isEqualTo(JSON.readTree("{\"rowCount\":5}"));
    }

    @Test
    void auditExportMasksMemberLabels() {
        insert(new Seed().action("user.update").about("user", UUID.randomUUID().toString(), "Lê Thị Lan"));
        insert(new Seed().at(TEN).about("admin_account", "acc-1", "Mai Tran"));

        String body = text(export(adminToken));

        assertThat(body).contains("L*** T*** L***").doesNotContain("Lê Thị Lan");
        assertThat(body).contains("\"Mai Tran\"");
    }

    @Test
    void recordingFailsOnExport() {
        insert(new Seed());
        doThrow(new DataAccessResourceFailureException("audit store down")).when(writer).writeOrThrow(any());

        ResponseEntity<byte[]> response = export(adminToken);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getContentType().toString()).startsWith("application/problem+json");
        assertThat(response.getHeaders().containsHeader(HttpHeaders.CONTENT_DISPOSITION)).isFalse();
        assertThat(text(response)).doesNotContain("mai.tran@example.com").doesNotContain("audit store down");
        assertThat(exportRows()).isZero();
    }

    @Test
    void plansOverAPopulatedTable() {
        try {
            jdbc.update("""
                    INSERT INTO admin_audit_event (id, actor_user_id, actor_email, actor_name, action,
                        resource_type, resource_id, resource_label, status, origin_source, occurred_at)
                    SELECT gen_random_uuid(),
                        ('00000000-0000-4000-8000-0000000000' || lpad((g % 20)::text, 2, '0'))::uuid,
                        CASE WHEN g % 500 = 0 THEN 'mai.tran@example.com' ELSE 'staff' || (g % 20) || '@example.com' END,
                        'Staff ' || (g % 20),
                        (ARRAY['user.update', 'user.create', 'support_kb_article.update', 'workflow.update'])[1 + g % 4],
                        (ARRAY['user', 'user', 'support_kb_article', 'workflow'])[1 + g % 4],
                        g::text, 'Item ' || g, 'success', 'http', now() - (g || ' seconds')::interval
                    FROM generate_series(1, 20000) g""");
            jdbc.execute("ANALYZE admin_audit_event");
            String actor = "00000000-0000-4000-8000-000000000003";
            String firstPageCursor = list(adminToken).path("nextCursor").asString();

            List<String[]> shapes = List.of(
                    new String[] {},
                    new String[] {"cursor", firstPageCursor},
                    new String[] {"actorUserId", actor},
                    new String[] {"action", "user.update"},
                    new String[] {"resourceType", "user"},
                    new String[] {"resourceType", "user", "resourceId", "4"},
                    new String[] {"q", "mai"});

            for (String[] shape : shapes) {
                String plan = planOf(shape);
                assertThat(plan).as(String.join("=", shape) + "\n" + plan)
                        .doesNotContain("Seq Scan on admin_audit_event")
                        .contains("idx_admin_audit_event");
            }
        } finally {
            jdbc.update("DELETE FROM admin_audit_event");
            // Statistics and index pages outlive the rows; later ITs on the shared database would plan against them.
            jdbc.execute("VACUUM ANALYZE admin_audit_event");
            jdbc.execute("REINDEX TABLE admin_audit_event");
        }
    }

    /** Runs the request, takes the statement the viewer actually executed and returns its EXPLAIN. */
    private String planOf(String... shape) {
        Mockito.clearInvocations(namedJdbc);
        assertThat(call(adminToken, String.class, BASE, shape).getStatusCode()).isEqualTo(HttpStatus.OK);
        Invocation page = Mockito.mockingDetails(namedJdbc).getInvocations().stream()
                .filter(call -> call.getMethod().getName().equals("query")
                        && call.getArguments().length == 3
                        && call.getArgument(0) instanceof String sql
                        && sql.contains("FROM admin_audit_event") && sql.contains("LIMIT"))
                .findFirst().orElseThrow(() -> new AssertionError("the viewer ran no list query"));
        String sql = page.getArgument(0);
        SqlParameterSource params = page.getArgument(1);
        ParsedSql parsed = NamedParameterUtils.parseSqlStatement(sql);
        String positional = NamedParameterUtils.substituteNamedParameters(parsed, params);
        Object[] values = NamedParameterUtils.buildValueArray(parsed, params, null);
        return String.join("\n", jdbc.queryForList("EXPLAIN " + positional, String.class, values));
    }

    /** Passes the cursor's shape check but its id part is no uuid, so the payload itself must be checked. */
    private static String cursorWithAnIdThatIsNoUuid() {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("1:" + "-".repeat(36)).getBytes(StandardCharsets.UTF_8));
    }
}
