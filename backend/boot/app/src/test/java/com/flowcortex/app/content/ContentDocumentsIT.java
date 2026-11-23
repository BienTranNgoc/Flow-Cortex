package com.flowcortex.app.content;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.AbstractPostgresBootTest;
import com.flowcortex.content.api.ContentDocumentDefinition;
import com.flowcortex.content.api.LocalizedText;
import com.flowcortex.content.document.PublishedDocuments;
import com.flowcortex.shared.security.authz.Permission;
import com.flowcortex.user.api.UserService;
import com.flowcortex.user.api.UserView;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The versioned documents over HTTP against a real database: draft saves with a version token,
 * publication, snapshots, and what anonymous readers are served. Two test-only definitions stand in
 * for the documents later changes register: {@code it-doc} keeps a public history, {@code it-private}
 * does not.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class ContentDocumentsIT extends AbstractPostgresBootTest {

    private static final String PASSWORD = "correct-password-1";
    private static final String ADMIN = "/api/v1/admin/content/documents";
    private static final String PUBLIC = "/api/v1/content/documents";
    private static final String PUBLIC_CACHE = "public, max-age=0, stale-while-revalidate=10";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final List<String> SLUGS = List.of("it-doc", "it-private");

    @TestConfiguration
    static class Definitions {

        @Bean
        ContentDocumentDefinition itDoc() {
            return new ContentDocumentDefinition("it-doc", new LocalizedText("Tài liệu thử", "Test document"),
                    List.of("richText", "media"), true);
        }

        @Bean
        ContentDocumentDefinition itPrivate() {
            return new ContentDocumentDefinition("it-private", new LocalizedText("Tài liệu kín", "Private document"),
                    List.of("richText"), false);
        }
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserService userService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PublishedDocuments publishedDocuments;

    private UserView owner;
    private UserView editorA;
    private UserView editorB;
    private String tokenA;
    private String tokenB;
    private final List<UUID> mediaIds = new ArrayList<>();

    @BeforeEach
    void arrange() {
        clearDocuments();
        owner = userService.createInitialAdmin(email("owner"), PASSWORD, "Owner");
        editorA = userService.createUser(owner.id(), email("editor-a"), PASSWORD, "Editor A", "CONTENT_EDITOR");
        editorB = userService.createUser(owner.id(), email("editor-b"), PASSWORD, "Editor B", "CONTENT_EDITOR");
        tokenA = login(editorA.email());
        tokenB = login(editorB.email());
    }

    @AfterEach
    void cleanUp() {
        clearDocuments();
        mediaIds.forEach(id -> jdbc.update("DELETE FROM content_media WHERE id = ?", id));
        mediaIds.clear();
    }

    private void clearDocuments() {
        jdbc.update("DELETE FROM content_document_revision WHERE slug LIKE 'it-%'");
        jdbc.update("DELETE FROM content_document WHERE slug LIKE 'it-%'");
        SLUGS.forEach(publishedDocuments::invalidate);
    }

    @Test
    void readingADraftNeverCreatesARow() {
        ResponseEntity<JsonNode> response = get(ADMIN + "/it-doc", tokenA);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("version").asInt()).isZero();
        assertThat(response.getBody().get("sections")).isEmpty();
        assertThat(count("content_document", "it-doc")).isZero();
        assertThat(count("content_document_revision", "it-doc")).isZero();
    }

    @Test
    void theFirstSaveCarriesVersionZero() {
        ResponseEntity<JsonNode> saved = save("it-doc", tokenA, 0, richText("Xin chào", "Hello"), seo("Tiêu đề", "Title"));

        assertThat(saved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(saved.getBody().get("version").asInt()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT version FROM content_document WHERE slug = 'it-doc' AND status = 'draft'", Integer.class))
                .isEqualTo(1);
        assertThat(count("content_document_revision", "it-doc")).isZero();
        assertThat(get(ADMIN + "/it-doc", tokenA).getBody().get("version").asInt()).isEqualTo(1);
    }

    @Test
    void aStaleTokenIs409WithCurrentVersion() {
        save("it-doc", tokenA, 0, richText("Một", "One"), seo("T", "T"));
        save("it-doc", tokenA, 1, richText("Hai", "Two"), seo("T", "T"));

        ResponseEntity<JsonNode> stale = save("it-doc", tokenB, 1, richText("Ba", "Three"), seo("T", "T"));

        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(stale.getBody().get("type").asString()).endsWith("/version-conflict");
        assertThat(stale.getBody().get("currentVersion").asInt()).isEqualTo(2);
        JsonNode draft = get(ADMIN + "/it-doc", tokenA).getBody();
        assertThat(draft.get("version").asInt()).isEqualTo(2);
        assertThat(draft.get("sections").get(0).get("data").get("body").get("en").asString()).isEqualTo("Two");
        assertThat(count("content_document_revision", "it-doc")).isEqualTo(1);
    }

    @Test
    void twoSavesWithOneTokenStoreExactlyOne() throws Exception {
        for (int round = 0; round < 3; round++) {
            clearDocuments();
            int token = round == 0 ? 0 : 1;
            if (token == 1) {
                save("it-doc", tokenA, 0, richText("Một", "One"), seo("T", "T"));
            }
            int storedBefore = token == 0 ? 0 : 1;

            List<ResponseEntity<JsonNode>> answers = raceTwoSaves(token);

            List<HttpStatus> statuses = answers.stream().map(a -> HttpStatus.valueOf(a.getStatusCode().value())).toList();
            assertThat(statuses).containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.CONFLICT);
            ResponseEntity<JsonNode> winner = answers.stream().filter(a -> a.getStatusCode().is2xxSuccessful())
                    .findFirst().orElseThrow();
            ResponseEntity<JsonNode> loser = answers.stream().filter(a -> a.getStatusCode().is4xxClientError())
                    .findFirst().orElseThrow();
            assertThat(winner.getBody().get("version").asInt()).isEqualTo(storedBefore + 1);
            assertThat(loser.getBody().get("currentVersion").asInt()).isEqualTo(storedBefore + 1);
            assertThat(jdbc.queryForObject(
                    "SELECT version FROM content_document WHERE slug = 'it-doc' AND status = 'draft'", Integer.class))
                    .isEqualTo(storedBefore + 1);
            assertThat(count("content_document_revision", "it-doc")).isEqualTo(storedBefore);
        }
    }

    @Test
    void publishCopiesTheNamedDraftForBothLanguagesAndBumpsTheVersion() {
        save("it-doc", tokenA, 0, richText("Một", "One"), seo("Tiêu đề", "Title"));
        save("it-doc", tokenA, 1, richText("Hai", "Two"), seo("Tiêu đề hai", "Title two"));

        ResponseEntity<JsonNode> first = publish("it-doc", tokenA, 2);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody().get("version").asInt()).isEqualTo(1);
        assertThat(first.getBody().get("publishedAt").asString()).isNotBlank();
        JsonNode published = get(PUBLIC + "/it-doc", null).getBody();
        assertThat(published.get("sections").get(0).get("data").get("body").get("vi").asString()).isEqualTo("Hai");
        assertThat(published.get("sections").get(0).get("data").get("body").get("en").asString()).isEqualTo("Two");
        assertThat(published.get("seoMeta").get("title").get("vi").asString()).isEqualTo("Tiêu đề hai");
        assertThat(jdbc.queryForObject(
                "SELECT version FROM content_document WHERE slug = 'it-doc' AND status = 'published'", Integer.class))
                .isEqualTo(1);

        save("it-doc", tokenA, 2, richText("Ba", "Three"), seo("Tiêu đề", "Title"));
        ResponseEntity<JsonNode> second = publish("it-doc", tokenA, 3);

        assertThat(second.getBody().get("version").asInt()).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT version FROM content_document WHERE slug = 'it-doc' AND status = 'published'", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void publishWithAnOldTokenIs409AndPublishesNothing() {
        save("it-doc", tokenA, 0, richText("Một", "One"), seo("T", "T"));
        save("it-doc", tokenB, 1, richText("Hai", "Two"), seo("T", "T"));

        ResponseEntity<JsonNode> response = publish("it-doc", tokenA, 1);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("type").asString()).endsWith("/version-conflict");
        assertThat(response.getBody().get("currentVersion").asInt()).isEqualTo(2);
        assertThat(publishedRows("it-doc")).isZero();
        assertThat(publicationSnapshots("it-doc")).isZero();
    }

    @Test
    void publishWithNothingSavedIs409() {
        ResponseEntity<JsonNode> response = publish("it-doc", tokenA, 0);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("type").asString()).endsWith("/nothing-to-publish");
        assertThat(publishedRows("it-doc")).isZero();
    }

    @Test
    void anIncompleteDraftIs422ListingEveryFieldAndPublishesNothing() {
        UUID media = libraryItem();
        ArrayNode sections = JSON.createArrayNode();
        sections.add(richTextSection("Xin chào", ""));
        sections.add(mediaSection(media, "", "Alt"));
        assertThat(save("it-doc", tokenA, 0, sections, seo("Tiêu đề", "")).getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<JsonNode> response = publish("it-doc", tokenA, 1);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(response.getBody().get("type").asString()).endsWith("/document-incomplete");
        List<String> fields = new ArrayList<>();
        response.getBody().get("fields").forEach(field -> fields.add(field.asString()));
        assertThat(fields).containsExactly("sections[0].data.body.en", "sections[1].data.alt.vi", "seoMeta.title.en");
        assertThat(publishedRows("it-doc")).isZero();
        assertThat(publicationSnapshots("it-doc")).isZero();
    }

    @Test
    void saveAndPublishLeaveTheirSnapshotsWithAuthors() {
        save("it-doc", tokenA, 0, richText("Một", "One"), seo("T", "T"));
        save("it-doc", tokenB, 1, richText("Hai", "Two"), seo("T", "T"));
        publish("it-doc", tokenB, 2);

        List<java.util.Map<String, Object>> rows = jdbc.queryForList(
                "SELECT kind, version, author_id, sections::text AS sections FROM content_document_revision"
                        + " WHERE slug = 'it-doc' ORDER BY kind");

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("kind")).isEqualTo("draft");
        assertThat(rows.get(0).get("version")).isEqualTo(1);
        assertThat(rows.get(0).get("author_id")).isEqualTo(editorA.id());
        assertThat((String) rows.get(0).get("sections")).contains("One");
        assertThat(rows.get(1).get("kind")).isEqualTo("publication");
        assertThat(rows.get(1).get("version")).isEqualTo(1);
        assertThat(rows.get(1).get("author_id")).isEqualTo(editorB.id());
        assertThat((String) rows.get(1).get("sections")).contains("Two");
    }

    @Test
    void aRefusedWriteLeavesNoSnapshot() {
        save("it-doc", tokenA, 0, richText("Một", ""), seo("T", ""));

        ResponseEntity<JsonNode> stale = save("it-doc", tokenA, 5, richText("x", "x"), seo("T", "T"));
        ArrayNode unknownMedia = JSON.createArrayNode();
        unknownMedia.add(mediaSection(UUID.randomUUID(), "a", "a"));
        ResponseEntity<JsonNode> invalid = save("it-doc", tokenA, 1, unknownMedia, seo("T", "T"));
        ResponseEntity<JsonNode> oldPublish = publish("it-doc", tokenA, 5);
        ResponseEntity<JsonNode> incomplete = publish("it-doc", tokenA, 1);

        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(invalid.getBody().get("type").asString()).endsWith("/document-invalid");
        assertThat(invalid.getBody().get("errors").has("sections[0].data.mediaId")).isTrue();
        assertThat(oldPublish.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(incomplete.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(count("content_document_revision", "it-doc")).isZero();
        assertThat(get(ADMIN + "/it-doc", tokenA).getBody().get("version").asInt()).isEqualTo(1);
    }

    @Test
    void snapshotsPageNewestFirst() {
        save("it-doc", tokenA, 0, richText("0", "0"), seo("T", "T"));
        for (int version = 1; version <= 25; version++) {
            save("it-doc", tokenA, version, richText("v" + version, "v" + version), seo("T", "T"));
        }

        JsonNode first = get(ADMIN + "/it-doc/revisions", tokenA).getBody();
        JsonNode second = get(ADMIN + "/it-doc/revisions?page=1", tokenA).getBody();

        assertThat(first.get("total").asLong()).isEqualTo(25);
        assertThat(first.get("size").asInt()).isEqualTo(20);
        assertThat(first.get("items")).hasSize(20);
        assertThat(first.get("items").get(0).get("version").asInt()).isEqualTo(25);
        assertThat(first.get("items").get(19).get("version").asInt()).isEqualTo(6);
        assertThat(first.get("items").get(0).get("kind").asString()).isEqualTo("draft");
        assertThat(first.get("items").get(0).get("authorEmail").asString()).isEqualTo(editorA.email());
        assertThat(second.get("items")).hasSize(5);
        assertThat(second.get("items").get(4).get("version").asInt()).isEqualTo(1);
        assertThat(get(ADMIN + "/it-doc/revisions?size=101", tokenA).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get(ADMIN + "/it-doc/revisions?size=100", tokenA).getStatusCode()).isEqualTo(HttpStatus.OK);

        String id = first.get("items").get(0).get("id").asString();
        JsonNode detail = get(ADMIN + "/it-doc/revisions/" + id, tokenA).getBody();
        assertThat(detail.get("sections").get(0).get("data").get("body").get("en").asString()).isEqualTo("v24");
    }

    @Test
    void aSnapshotThroughAnotherDocumentsRouteIs404() {
        save("it-doc", tokenA, 0, richText("Một", "One"), seo("T", "T"));
        save("it-doc", tokenA, 1, richText("Hai", "Two"), seo("T", "T"));
        String id = get(ADMIN + "/it-doc/revisions", tokenA).getBody().get("items").get(0).get("id").asString();

        assertThat(get(ADMIN + "/it-doc/revisions/" + id, tokenA).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get(ADMIN + "/it-private/revisions/" + id, tokenA).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get(ADMIN + "/it-doc/revisions/not-a-uuid", tokenA).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get(ADMIN + "/it-doc/revisions/" + UUID.randomUUID(), tokenA).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void publicReadIs404UntilPublishedAndThenServesThePublishedCopyNotTheDraft() {
        save("it-doc", tokenA, 0, richText("Bản một", "Version one"), seo("T", "T"));

        ResponseEntity<JsonNode> beforePublish = get(PUBLIC + "/it-doc", null);
        assertThat(beforePublish.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(beforePublish.getBody().get("type").asString()).endsWith("/document-not-found");
        ResponseEntity<JsonNode> unknown = get(PUBLIC + "/no-such-doc", null);
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(unknown.getBody().get("detail")).isEqualTo(beforePublish.getBody().get("detail"));

        publish("it-doc", tokenA, 1);
        save("it-doc", tokenA, 1, richText("Bản hai", "Version two"), seo("T", "T"));

        JsonNode served = get(PUBLIC + "/it-doc", null).getBody();
        assertThat(served.get("version").asInt()).isEqualTo(1);
        assertThat(served.get("slug").asString()).isEqualTo("it-doc");
        assertThat(served.get("sections").get(0).get("data").get("body").get("en").asString()).isEqualTo("Version one");
    }

    @Test
    void publicReadsCarryMaxAgeZeroAndNotFoundCarriesNoStore() {
        save("it-doc", tokenA, 0, richText("Một", "One"), seo("T", "T"));
        ResponseEntity<JsonNode> unpublished = get(PUBLIC + "/it-doc", null);
        ResponseEntity<JsonNode> noRevision = get(PUBLIC + "/it-doc/history/" + UUID.randomUUID(), null);
        publish("it-doc", tokenA, 1);
        String publication = get(PUBLIC + "/it-doc/history", null).getBody().get("items").get(0)
                .get("revisionId").asString();

        ResponseEntity<JsonNode> read = get(PUBLIC + "/it-doc", null);
        ResponseEntity<JsonNode> list = get(PUBLIC + "/it-doc/history", null);
        ResponseEntity<JsonNode> detail = get(PUBLIC + "/it-doc/history/" + publication, null);

        for (ResponseEntity<JsonNode> ok : List.of(read, list, detail)) {
            assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(ok.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).isEqualTo(PUBLIC_CACHE);
        }
        for (ResponseEntity<JsonNode> missing : List.of(unpublished, noRevision)) {
            assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(missing.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        }
    }

    @Test
    void aPublicationIsServedAtOnceByThePublishingInstance() {
        save("it-doc", tokenA, 0, richText("Một", "One"), seo("T", "T"));
        assertThat(get(PUBLIC + "/it-doc", null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        publish("it-doc", tokenA, 1);

        assertThat(get(PUBLIC + "/it-doc", null).getStatusCode()).isEqualTo(HttpStatus.OK);
        save("it-doc", tokenA, 1, richText("Hai", "Two"), seo("T", "T"));
        publish("it-doc", tokenA, 2);
        JsonNode served = get(PUBLIC + "/it-doc", null).getBody();
        assertThat(served.get("version").asInt()).isEqualTo(2);
        assertThat(served.get("sections").get(0).get("data").get("body").get("en").asString()).isEqualTo("Two");
    }

    @Test
    void publicHistoryListsOnlyPublications() {
        save("it-doc", tokenA, 0, richText("Một", "One"), seo("T", "T"));
        save("it-doc", tokenA, 1, richText("Hai", "Two"), seo("T", "T"));
        publish("it-doc", tokenA, 2);
        save("it-doc", tokenA, 2, richText("Ba", "Three"), seo("T", "T"));
        publish("it-doc", tokenA, 3);
        save("it-doc", tokenA, 3, richText("Bốn", "Four"), seo("T", "T"));

        JsonNode history = get(PUBLIC + "/it-doc/history", null).getBody();

        assertThat(history.get("total").asLong()).isEqualTo(2);
        assertThat(history.get("items")).hasSize(2);
        Instant newest = Instant.parse(history.get("items").get(0).get("publishedAt").asString());
        Instant oldest = Instant.parse(history.get("items").get(1).get("publishedAt").asString());
        assertThat(newest).isAfterOrEqualTo(oldest);
        JsonNode detail = get(PUBLIC + "/it-doc/history/" + history.get("items").get(0).get("revisionId").asString(),
                null).getBody();
        assertThat(detail.get("slug").asString()).isEqualTo("it-doc");
        assertThat(detail.get("sections").get(0).get("data").get("body").get("en").asString()).isEqualTo("Three");
        assertThat(detail.get("publishedAt").asString()).isNotBlank();
    }

    @Test
    void aDraftSnapshotIdOnThePublicDetailIs404() {
        save("it-doc", tokenA, 0, richText("Một", "One"), seo("T", "T"));
        save("it-doc", tokenA, 1, richText("Hai", "Two"), seo("T", "T"));
        publish("it-doc", tokenA, 2);
        JsonNode snapshots = get(ADMIN + "/it-doc/revisions", tokenA).getBody().get("items");
        String draftSnapshot = null;
        for (JsonNode snapshot : snapshots) {
            if ("draft".equals(snapshot.get("kind").asString())) {
                draftSnapshot = snapshot.get("id").asString();
            }
        }

        assertThat(draftSnapshot).isNotNull();
        assertThat(get(PUBLIC + "/it-doc/history/" + draftSnapshot, null).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get(PUBLIC + "/it-doc/history/not-a-uuid", null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void aDocumentWithoutPublicHistoryAnswersAnEmptyList() {
        save("it-private", tokenA, 0, richText("Một", "One"), seo("T", "T"));
        publish("it-private", tokenA, 1);
        String publication = jdbc.queryForObject(
                "SELECT id::text FROM content_document_revision WHERE slug = 'it-private' AND kind = 'publication'",
                String.class);

        for (String slug : List.of("it-private", "no-such-doc")) {
            ResponseEntity<JsonNode> list = get(PUBLIC + "/" + slug + "/history", null);
            assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(list.getBody().get("items")).isEmpty();
            assertThat(list.getBody().get("total").asLong()).isZero();
        }
        assertThat(get(PUBLIC + "/it-private/history/" + publication, null).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get(PUBLIC + "/it-private", null).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void anUnregisteredSlugIs404OnEveryRouteButTheHistoryList() {
        String slug = "/no-such-doc";
        UUID id = UUID.randomUUID();

        assertThat(get(ADMIN + slug, tokenA).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(save("no-such-doc", tokenA, 0, richText("a", "a"), seo("T", "T")).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(publish("no-such-doc", tokenA, 1).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get(ADMIN + slug + "/revisions", tokenA).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get(ADMIN + slug + "/revisions/" + id, tokenA).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get(PUBLIC + slug, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get(PUBLIC + slug + "/history/" + id, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get(PUBLIC + slug + "/history", null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM content_document WHERE slug = 'no-such-doc'",
                Integer.class)).isZero();
    }

    @Test
    void viewWithoutManageReadsButCannotSave() {
        UserView viewer = userService.createUser(owner.id(), email("viewer"), PASSWORD, "Viewer", "CUSTOMER_SUPPORT");
        String token = login(viewer.email());
        jdbc.update("INSERT INTO role_permission (role_code, permission_key) VALUES ('CUSTOMER_SUPPORT', ?)",
                Permission.CONTENT_DOCUMENT_VIEW.key());
        try {
            ResponseEntity<JsonNode> read = get(ADMIN + "/it-doc", token);
            ResponseEntity<JsonNode> list = get(ADMIN, token);
            ResponseEntity<JsonNode> write = save("it-doc", token, 0, richText("a", "a"), seo("T", "T"));
            ResponseEntity<JsonNode> publish = publish("it-doc", token, 1);

            assertThat(read.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(write.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(publish.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(count("content_document", "it-doc")).isZero();
        } finally {
            jdbc.update("DELETE FROM role_permission WHERE role_code = 'CUSTOMER_SUPPORT' AND permission_key = ?",
                    Permission.CONTENT_DOCUMENT_VIEW.key());
        }
    }

    @Test
    void aMemberWithoutKeysIs403OnTheAdminDraftRead() {
        UserView member = userService.createUser(owner.id(), email("member"), PASSWORD, "Member", "MEMBER");

        assertThat(get(ADMIN + "/it-doc", login(member.email())).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get(ADMIN + "/it-doc", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void noPublicRouteServesDraftContent() {
        String secret = "SECRET-DRAFT-" + UUID.randomUUID();
        save("it-doc", tokenA, 0, richText(secret, secret), seo(secret, secret));
        save("it-doc", tokenA, 1, richText(secret, secret), seo(secret, secret));
        String draftSnapshot = get(ADMIN + "/it-doc/revisions", tokenA).getBody().get("items").get(0)
                .get("id").asString();

        List<String> bodies = new ArrayList<>();
        for (String token : new String[] {null, tokenA}) {
            bodies.add(exchangeText(HttpMethod.GET, PUBLIC + "/it-doc", token));
            bodies.add(exchangeText(HttpMethod.GET, PUBLIC + "/it-doc/history", token));
            bodies.add(exchangeText(HttpMethod.GET, PUBLIC + "/it-doc/history/" + draftSnapshot, token));
            bodies.add(exchangeText(HttpMethod.GET, PUBLIC + "/it-doc/preview", token));
        }

        assertThat(bodies).noneMatch(body -> body.contains(secret));
        ResponseEntity<String> write = restTemplate.exchange(PUBLIC + "/it-doc", HttpMethod.PUT,
                new HttpEntity<>(body(2, richText("a", "a"), seo("T", "T")), new HttpHeaders()), String.class);
        assertThat(write.getStatusCode().is4xxClientError()).isTrue();
    }

    @Test
    void theAdminListHoldsEveryRegisteredDocumentWithItsVersions() {
        save("it-doc", tokenA, 0, richText("Một", "One"), seo("T", "T"));
        save("it-doc", tokenA, 1, richText("Hai", "Two"), seo("T", "T"));
        publish("it-doc", tokenA, 2);

        JsonNode list = get(ADMIN, tokenA).getBody();

        List<String> slugs = new ArrayList<>();
        list.forEach(item -> slugs.add(item.get("slug").asString()));
        assertThat(slugs).contains("it-doc", "it-private").doesNotContain("dev-sample");
        JsonNode itDoc = null;
        JsonNode itPrivate = null;
        for (JsonNode item : list) {
            if ("it-doc".equals(item.get("slug").asString())) {
                itDoc = item;
            } else if ("it-private".equals(item.get("slug").asString())) {
                itPrivate = item;
            }
        }
        assertThat(itDoc.get("draftVersion").asInt()).isEqualTo(2);
        assertThat(itDoc.get("publishedVersion").asInt()).isEqualTo(1);
        assertThat(itDoc.get("publicHistory").asBoolean()).isTrue();
        assertThat(itDoc.get("title").get("vi").asString()).isEqualTo("Tài liệu thử");
        assertThat(itDoc.get("sectionTypes")).hasSize(2);
        assertThat(itPrivate.get("draftVersion").asInt()).isZero();
        assertThat(itPrivate.get("publishedVersion").asInt()).isZero();
        assertThat(itPrivate.get("publishedAt").isNull()).isTrue();
    }

    @Test
    void devSampleIsUnknownWithoutTheDevProfile() {
        assertThat(get(PUBLIC + "/dev-sample", null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get(PUBLIC + "/dev-sample/history", null).getBody().get("items")).isEmpty();
        assertThat(get(ADMIN + "/dev-sample", tokenA).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private List<ResponseEntity<JsonNode>> raceTwoSaves(int token) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<ResponseEntity<JsonNode>>> futures = new ArrayList<>();
            for (String bearer : List.of(tokenA, tokenB)) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return save("it-doc", bearer, token, richText("Từ " + bearer.hashCode(), "From " + bearer.hashCode()),
                            seo("T", "T"));
                }));
            }
            ready.await();
            go.countDown();
            List<ResponseEntity<JsonNode>> answers = new ArrayList<>();
            for (Future<ResponseEntity<JsonNode>> future : futures) {
                answers.add(future.get());
            }
            return answers;
        } finally {
            pool.shutdownNow();
        }
    }

    private ResponseEntity<JsonNode> save(String slug, String token, int version, JsonNode sections, JsonNode seoMeta) {
        return exchange(HttpMethod.PUT, ADMIN + "/" + slug, token, body(version, sections, seoMeta));
    }

    private ResponseEntity<JsonNode> publish(String slug, String token, int version) {
        ObjectNode body = JSON.createObjectNode();
        body.put("version", version);
        return exchange(HttpMethod.POST, ADMIN + "/" + slug + "/publish", token, body);
    }

    private ResponseEntity<JsonNode> get(String path, String token) {
        return exchange(HttpMethod.GET, path, token, null);
    }

    private ResponseEntity<JsonNode> exchange(HttpMethod method, String path, String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return restTemplate.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class);
    }

    private String exchangeText(HttpMethod method, String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return restTemplate.exchange(path, method, new HttpEntity<>(null, headers), String.class).getBody();
    }

    private static ObjectNode body(int version, JsonNode sections, JsonNode seoMeta) {
        ObjectNode body = JSON.createObjectNode();
        body.put("version", version);
        body.set("sections", sections);
        body.set("seoMeta", seoMeta);
        return body;
    }

    private static ArrayNode richText(String vi, String en) {
        ArrayNode sections = JSON.createArrayNode();
        sections.add(richTextSection(vi, en));
        return sections;
    }

    private static ObjectNode richTextSection(String vi, String en) {
        ObjectNode data = JSON.createObjectNode();
        data.set("body", text(vi, en));
        return section("richText", data);
    }

    private static ObjectNode mediaSection(UUID mediaId, String altVi, String altEn) {
        ObjectNode data = JSON.createObjectNode();
        data.put("mediaId", mediaId.toString());
        data.set("alt", text(altVi, altEn));
        return section("media", data);
    }

    private static ObjectNode section(String type, ObjectNode data) {
        ObjectNode section = JSON.createObjectNode();
        section.put("type", type);
        section.put("schemaVersion", 1);
        section.set("data", data);
        return section;
    }

    private static ObjectNode seo(String titleVi, String titleEn) {
        ObjectNode seo = JSON.createObjectNode();
        seo.set("title", text(titleVi, titleEn));
        seo.set("description", text("Mô tả", "Description"));
        return seo;
    }

    private static ObjectNode text(String vi, String en) {
        ObjectNode text = JSON.createObjectNode();
        text.put("vi", vi);
        text.put("en", en);
        return text;
    }

    private UUID libraryItem() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO content_media (id, kind, content_type, size_bytes, bucket, object_key, file_name,"
                + " uploaded_by, created_at) VALUES (?, 'image', 'image/png', 10, ?, ?, 'a.png', ?, now())",
                id, MEDIA_BUCKET, "content/" + id, owner.id());
        mediaIds.add(id);
        return id;
    }

    private int count(String table, String slug) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE slug = ?", Integer.class, slug);
    }

    private int publishedRows(String slug) {
        return jdbc.queryForObject("SELECT count(*) FROM content_document WHERE slug = ? AND status = 'published'",
                Integer.class, slug);
    }

    private int publicationSnapshots(String slug) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM content_document_revision WHERE slug = ? AND kind = 'publication'",
                Integer.class, slug);
    }

    record LoginRequest(String email, String password) {
    }

    private String login(String email) {
        JsonNode token = restTemplate.postForEntity("/api/v1/auth/login", new LoginRequest(email, PASSWORD),
                JsonNode.class).getBody();
        return token.get("accessToken").asString();
    }

    private static String email(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@example.com";
    }
}
