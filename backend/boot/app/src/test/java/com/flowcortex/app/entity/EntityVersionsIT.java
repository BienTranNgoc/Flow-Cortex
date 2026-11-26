package com.flowcortex.app.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.app.workflow.AbstractWorkflowHttpIT;
import com.flowcortex.space.api.SessionSpaces;
import com.flowcortex.user.api.UserView;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Entity versions over HTTP with spaces on and a real login: the newest-first list with its run counts, a version
 * read as an entity, and restore as a new version under the same base-version and handle rules as an edit.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "flowcortex.spaces.enabled=true")
@AutoConfigureTestRestTemplate
class EntityVersionsIT extends AbstractWorkflowHttpIT {

    private static final String ENTITIES = "/api/v1/entities";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SessionSpaces sessionSpaces;

    @Autowired
    private MessageSource messages;

    record AssetView(UUID id, String kind, String title, String mimeType) {
    }

    record PartView(UUID id, String role, String label, int sort, AssetView asset) {
    }

    record EntityView(UUID id, String kind, String handle, String name, Map<String, Object> fields,
            String categoryLabel, List<String> tags, List<PartView> parts, int version, Instant createdAt,
            Instant updatedAt) {
    }

    record ChangeView(String code, List<String> added, List<String> removed, Integer from) {
    }

    record VersionItem(int version, Instant createdAt, boolean current, List<ChangeView> changes, int usedInRuns) {
    }

    record VersionPage(List<VersionItem> items, int page, int size, long total) {
    }

    record VersionDetail(int version, Instant createdAt, boolean current, EntityView entity) {
    }

    record DroppedPart(UUID assetId, String role, String label) {
    }

    record Restored(UUID id, String kind, String handle, String name, Map<String, Object> fields,
            String categoryLabel, List<String> tags, List<PartView> parts, int version, Instant createdAt,
            Instant updatedAt, List<DroppedPart> droppedParts) {
    }

    record ProblemView(int status, String detail, Map<String, String> errors) {
    }

    private record Member(UUID id, String token, UUID space) {
    }

    private Member member(String prefix) {
        UserView user = newUser(prefix);
        return new Member(user.id(), loginAs(user), sessionSpaces.sessionSpace(user.id()).spaceId());
    }

    @Test
    void versionsListNewestFirstWithCurrentAndUsedInRuns() {
        Member a = member("entity-versions-list");
        EntityView entity = create(a, Map.of("kind", "brand", "name", "Nodi", "tags", List.of("Mùa Hè")));
        patch(a, entity.id(), Map.of("baseVersion", 1, "name", "Nodi 2"));
        patch(a, entity.id(), Map.of("baseVersion", 2, "tags", List.of("Mùa Hè", "Sale"), "categoryLabel", "Quần áo"));
        seedRun(a, entity.id(), 2, Instant.parse("2026-11-20T08:00:00Z"));
        seedRun(a, entity.id(), 2, Instant.parse("2026-11-20T09:00:00Z"));
        seedRun(a, entity.id(), 3, null);

        VersionPage page = versions(a, entity.id(), "");

        assertThat(page.items()).extracting(VersionItem::version).containsExactly(3, 2, 1);
        assertThat(page.items()).extracting(VersionItem::current).containsExactly(true, false, false);
        assertThat(page.items()).extracting(VersionItem::usedInRuns).containsExactly(0, 2, 0);
        assertThat(page.total()).isEqualTo(3);
        assertThat(page.items().get(0).changes()).extracting(ChangeView::code)
                .containsExactly("tagsChanged", "categoryChanged");
        assertThat(page.items().get(0).changes().get(0).added()).containsExactly("Sale");
        assertThat(page.items().get(1).changes()).extracting(ChangeView::code).containsExactly("renamed");
        assertThat(page.items().get(2).changes()).extracting(ChangeView::code).containsExactly("created");
        VersionPage second = versions(a, entity.id(), "?page=1&size=2");
        assertThat(second.items()).extracting(VersionItem::version).containsExactly(1);
        assertThat(second.total()).isEqualTo(3);
        assertThat(status(HttpMethod.GET, ENTITIES + "/" + entity.id() + "/versions?size=51", a, null))
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(status(HttpMethod.GET, ENTITIES + "/" + entity.id() + "/versions?size=0", a, null))
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void readingAVersionAnswersItsSnapshot() {
        Member a = member("entity-versions-read");
        UUID image = seedAsset(a, "image");
        EntityView entity = create(a, Map.of("kind", "product", "name", "Tee", "tags", List.of("Sale"),
                "categoryLabel", "Áo", "parts", List.of(Map.of("assetId", image, "role", "product_photo"))));
        patch(a, entity.id(), Map.of("baseVersion", 1, "name", "Tee 2", "parts", List.of(), "tags", List.of()));

        VersionDetail old = version(a, entity.id(), 1);

        assertThat(old.version()).isEqualTo(1);
        assertThat(old.current()).isFalse();
        assertThat(old.entity().id()).isEqualTo(entity.id());
        assertThat(old.entity().version()).isEqualTo(1);
        assertThat(old.entity().name()).isEqualTo("Tee");
        assertThat(old.entity().handle()).isEqualTo("tee");
        assertThat(old.entity().tags()).containsExactly("Sale");
        assertThat(old.entity().categoryLabel()).isEqualTo("Áo");
        assertThat(old.entity().parts()).hasSize(1);
        assertThat(old.entity().parts().get(0).asset().id()).isEqualTo(image);
        assertThat(old.entity().parts().get(0).role()).isEqualTo("product_photo");
        VersionDetail current = version(a, entity.id(), 2);
        assertThat(current.current()).isTrue();
        assertThat(current.entity().name()).isEqualTo("Tee 2");
        assertThat(current.entity().parts()).isEmpty();
    }

    @Test
    void missingVersionIs404VersionNotFound() {
        Member a = member("entity-versions-missing");
        EntityView entity = create(a, Map.of("kind", "brand", "name", "Nodi"));
        String expected = messages.getMessage("entity.version.notFound", null, Locale.ENGLISH);

        for (String path : List.of("/versions/9", "/versions/0", "/versions/-1")) {
            ResponseEntity<ProblemView> missing = send(HttpMethod.GET, ENTITIES + "/" + entity.id() + path, a, null,
                    ProblemView.class);
            assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(missing.getBody().detail()).isEqualTo(expected);
        }
        ResponseEntity<ProblemView> restore = send(HttpMethod.POST, ENTITIES + "/" + entity.id() + "/versions/9/restore",
                a, Map.of("baseVersion", 1), ProblemView.class);
        assertThat(restore.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(restore.getBody().detail()).isEqualTo(expected);
        assertThat(get(a, entity.id()).version()).isEqualTo(1);
        assertThat(status(HttpMethod.GET, ENTITIES + "/" + entity.id() + "/versions/abc", a, null))
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void restoreWritesANewVersionFromTheOldContent() {
        Member a = member("entity-versions-restore");
        UUID image = seedAsset(a, "image");
        EntityView entity = create(a, Map.of("kind", "brand", "name", "Nodi", "fields", Map.of("voice", "calm"),
                "tags", List.of("Sale"), "categoryLabel", "Áo",
                "parts", List.of(Map.of("assetId", image, "role", "logo"))));
        patch(a, entity.id(), Map.of("baseVersion", 1, "name", "Nodi 2", "handle", "nodi-two",
                "fields", Map.of("voice", "loud"), "tags", List.of("Winter"), "parts", List.of()));
        patch(a, entity.id(), Map.of("baseVersion", 2, "name", "Nodi 3"));
        patch(a, entity.id(), Map.of("baseVersion", 3, "categoryLabel", "Quần"));

        ResponseEntity<Restored> restored = restore(a, entity.id(), 1, Map.of("baseVersion", 4), Restored.class);

        assertThat(restored.getStatusCode()).isEqualTo(HttpStatus.OK);
        Restored body = restored.getBody();
        assertThat(body.version()).isEqualTo(5);
        assertThat(body.id()).isEqualTo(entity.id());
        assertThat(body.name()).isEqualTo("Nodi");
        assertThat(body.handle()).isEqualTo(entity.handle());
        assertThat(body.fields()).isEqualTo(Map.of("voice", "calm"));
        assertThat(body.tags()).containsExactly("Sale");
        assertThat(body.categoryLabel()).isEqualTo("Áo");
        assertThat(body.parts()).extracting(part -> part.asset().id()).containsExactly(image);
        assertThat(body.droppedParts()).isEmpty();
        assertThat(get(a, entity.id())).isEqualTo(new EntityView(body.id(), body.kind(), body.handle(), body.name(),
                body.fields(), body.categoryLabel(), body.tags(), body.parts(), body.version(), body.createdAt(),
                body.updatedAt()));
        VersionPage page = versions(a, entity.id(), "");
        assertThat(page.items()).extracting(VersionItem::version).containsExactly(5, 4, 3, 2, 1);
        assertThat(page.items().get(0).changes()).hasSize(1);
        assertThat(page.items().get(0).changes().get(0).code()).isEqualTo("restored");
        assertThat(page.items().get(0).changes().get(0).from()).isEqualTo(1);
        assertThat(version(a, entity.id(), 5).entity().name()).isEqualTo("Nodi");
        restore(a, entity.id(), 5, Map.of("baseVersion", 5), Restored.class);
        assertThat(get(a, entity.id()).version()).isEqualTo(6);
    }

    @Test
    void restoreDropsPartsWhoseAssetIsGone() {
        Member a = member("entity-versions-dropped");
        UUID front = seedAsset(a, "image");
        UUID back = seedAsset(a, "image");
        UUID gone = seedAsset(a, "image");
        EntityView entity = create(a, Map.of("kind", "product", "name", "Tee", "parts", List.of(
                Map.of("assetId", front, "role", "product_photo", "label", "Front"),
                Map.of("assetId", gone, "role", "back", "label", "Rear"),
                Map.of("assetId", back, "role", "detail"))));
        patch(a, entity.id(), Map.of("baseVersion", 1, "name", "Tee 2"));
        jdbc.update("DELETE FROM space_asset WHERE id = ?", gone);

        ResponseEntity<Restored> restored = restore(a, entity.id(), 1, Map.of("baseVersion", 2), Restored.class);

        assertThat(restored.getStatusCode()).isEqualTo(HttpStatus.OK);
        Restored body = restored.getBody();
        assertThat(body.droppedParts()).containsExactly(new DroppedPart(gone, "back", "Rear"));
        assertThat(body.parts()).extracting(PartView::sort).containsExactly(0, 1);
        assertThat(body.parts()).extracting(part -> part.asset().id()).containsExactly(front, back);
        assertThat(body.parts()).extracting(PartView::label).containsExactly("Front", null);
        assertThat(jdbc.queryForList("SELECT sort FROM space_entity_part WHERE entity_id = ? ORDER BY sort",
                Integer.class, entity.id())).containsExactly(0, 1);
    }

    @Test
    void restoreOfAHandleNowTakenIs409AndWritesNothing() {
        Member a = member("entity-versions-handle");
        EntityView entity = create(a, Map.of("kind", "brand", "name", "Nodi", "handle", "nodi"));
        patch(a, entity.id(), Map.of("baseVersion", 1, "handle", "nodi-old", "name", "Nodi old"));
        create(a, Map.of("kind", "brand", "name", "Other", "handle", "nodi"));

        ResponseEntity<ProblemView> taken = restore(a, entity.id(), 1, Map.of("baseVersion", 2), ProblemView.class);

        assertThat(taken.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(taken.getBody().detail())
                .isEqualTo(messages.getMessage("entity.handleTaken", null, Locale.ENGLISH));
        EntityView current = get(a, entity.id());
        assertThat(current.version()).isEqualTo(2);
        assertThat(current.handle()).isEqualTo("nodi-old");
        assertThat(current.name()).isEqualTo("Nodi old");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM space_entity_version WHERE entity_id = ?", Long.class,
                entity.id())).isEqualTo(2);
    }

    @Test
    void restoreWithAStaleBaseVersionIs409() {
        Member a = member("entity-versions-stale");
        EntityView entity = create(a, Map.of("kind", "brand", "name", "Nodi"));
        patch(a, entity.id(), Map.of("baseVersion", 1, "name", "Nodi 2"));

        ResponseEntity<ProblemView> stale = restore(a, entity.id(), 1, Map.of("baseVersion", 1), ProblemView.class);
        ResponseEntity<ProblemView> missing = restore(a, entity.id(), 1, Map.of(), ProblemView.class);

        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(stale.getBody().detail())
                .isEqualTo(messages.getMessage("entity.versionConflict", null, Locale.ENGLISH));
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(missing.getBody().errors()).containsKey("baseVersion");
        EntityView current = get(a, entity.id());
        assertThat(current.version()).isEqualTo(2);
        assertThat(current.name()).isEqualTo("Nodi 2");
    }

    @Test
    void twoRestoresFromTheSameBaseVersionOneWinsOne409() throws Exception {
        Member a = member("entity-versions-race");
        EntityView entity = create(a, Map.of("kind", "brand", "name", "Nodi"));
        patch(a, entity.id(), Map.of("baseVersion", 1, "name", "Nodi 2"));

        List<HttpStatus> statuses = race(
                () -> status(HttpMethod.POST, ENTITIES + "/" + entity.id() + "/versions/1/restore", a,
                        Map.of("baseVersion", 2)),
                () -> status(HttpMethod.POST, ENTITIES + "/" + entity.id() + "/versions/1/restore", a,
                        Map.of("baseVersion", 2)));

        assertThat(statuses).containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.CONFLICT);
        assertThat(get(a, entity.id()).version()).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM space_entity_version WHERE entity_id = ?", Long.class,
                entity.id())).isEqualTo(3);
    }

    @Test
    void versionsOfATrashedEntityAre404() {
        Member a = member("entity-versions-trash-a");
        Member b = member("entity-versions-trash-b");
        EntityView entity = create(a, Map.of("kind", "brand", "name", "Nodi"));
        patch(a, entity.id(), Map.of("baseVersion", 1, "name", "Nodi 2"));
        assertThat(versions(a, entity.id(), "").items()).hasSize(2);
        assertThat(status(HttpMethod.GET, ENTITIES + "/" + entity.id() + "/versions", b, null))
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(status(HttpMethod.POST, ENTITIES + "/" + entity.id() + "/versions/1/restore", b,
                Map.of("baseVersion", 2))).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(status(HttpMethod.DELETE, ENTITIES + "/" + entity.id(), a, null)).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(status(HttpMethod.GET, ENTITIES + "/" + entity.id() + "/versions", a, null))
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(status(HttpMethod.GET, ENTITIES + "/" + entity.id() + "/versions/1", a, null))
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(status(HttpMethod.POST, ENTITIES + "/" + entity.id() + "/versions/1/restore", a,
                Map.of("baseVersion", 2))).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM space_entity_version WHERE entity_id = ?", Long.class,
                entity.id())).isEqualTo(2);
    }

    @Test
    void malformedIdOnVersionsIs404() {
        Member a = member("entity-versions-malformed");
        ResponseEntity<String> random = send(HttpMethod.GET, ENTITIES + "/" + UUID.randomUUID() + "/versions", a, null,
                String.class);
        assertThat(random.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        for (String path : List.of("/not-a-uuid/versions", "/not-a-uuid/versions/1")) {
            ResponseEntity<String> malformed = send(HttpMethod.GET, ENTITIES + path, a, null, String.class);
            assertThat(malformed.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(withoutInstance(malformed.getBody())).isEqualTo(withoutInstance(random.getBody()));
        }
        ResponseEntity<String> restore = send(HttpMethod.POST, ENTITIES + "/not-a-uuid/versions/1/restore", a,
                Map.of("baseVersion", 1), String.class);
        assertThat(restore.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(withoutInstance(restore.getBody())).isEqualTo(withoutInstance(random.getBody()));
    }

    /** The problem body without {@code instance}, which only echoes the path the caller itself asked for. */
    private static String withoutInstance(String problem) {
        return problem.replaceFirst("\"instance\":\"[^\"]*\",?", "");
    }

    private EntityView create(Member member, Map<String, Object> body) {
        ResponseEntity<EntityView> created = send(HttpMethod.POST, ENTITIES, member, body, EntityView.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return created.getBody();
    }

    private EntityView get(Member member, UUID id) {
        ResponseEntity<EntityView> found = send(HttpMethod.GET, ENTITIES + "/" + id, member, null, EntityView.class);
        assertThat(found.getStatusCode()).isEqualTo(HttpStatus.OK);
        return found.getBody();
    }

    private EntityView patch(Member member, UUID id, Map<String, Object> body) {
        ResponseEntity<EntityView> patched = send(HttpMethod.PATCH, ENTITIES + "/" + id, member, body,
                EntityView.class);
        assertThat(patched.getStatusCode()).isEqualTo(HttpStatus.OK);
        return patched.getBody();
    }

    private VersionPage versions(Member member, UUID id, String query) {
        ResponseEntity<VersionPage> page = send(HttpMethod.GET, ENTITIES + "/" + id + "/versions" + query, member, null,
                VersionPage.class);
        assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
        return page.getBody();
    }

    private VersionDetail version(Member member, UUID id, int n) {
        ResponseEntity<VersionDetail> found = send(HttpMethod.GET, ENTITIES + "/" + id + "/versions/" + n, member, null,
                VersionDetail.class);
        assertThat(found.getStatusCode()).isEqualTo(HttpStatus.OK);
        return found.getBody();
    }

    private <T> ResponseEntity<T> restore(Member member, UUID id, int n, Map<String, Object> body, Class<T> type) {
        return send(HttpMethod.POST, ENTITIES + "/" + id + "/versions/" + n + "/restore", member, body, type);
    }

    private HttpStatus status(HttpMethod method, String path, Member member, Object body) {
        return HttpStatus.valueOf(send(method, path, member, body, String.class).getStatusCode().value());
    }

    private <T> ResponseEntity<T> send(HttpMethod method, String path, Member member, Object body, Class<T> type) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(member.token());
        return restTemplate.exchange(path, method, new HttpEntity<>(body, headers), type);
    }

    /** Runs both calls on their own threads released together, and answers their results in call order. */
    @SafeVarargs
    private static <T> List<T> race(Callable<T>... calls) throws Exception {
        CyclicBarrier start = new CyclicBarrier(calls.length);
        ExecutorService pool = Executors.newFixedThreadPool(calls.length);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> call : calls) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return call.call();
                }));
            }
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    /** A run row of the entity at {@code version}; {@code completedAt} null means the run is still going. */
    private void seedRun(Member owner, UUID entityId, int version, Instant completedAt) {
        jdbc.update("INSERT INTO space_entity_run (run_id, entity_id, space_id, version, created_at, completed_at) "
                + "VALUES (?, ?, ?, ?, ?, ?)", UUID.randomUUID(), entityId, owner.space(), version,
                Timestamp.from(Instant.now()), completedAt == null ? null : Timestamp.from(completedAt));
    }

    private UUID seedAsset(Member owner, String kind) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO space_asset (id, space_id, owner_id, media_asset_id, source, kind, title, title_base,
                    title_version, bucket, object_key, mime_type, bytes, run_id, created_at)
                VALUES (?, ?, ?, ?, 'upload', ?, ?, ?, 1, 'flowcortex-media', ?, ?, 1, ?, ?)""",
                id, owner.space(), owner.id(), UUID.randomUUID(), kind, "Asset " + id, "asset", "entity-it/" + id,
                "audio".equals(kind) ? "audio/mpeg" : "image/png", UUID.randomUUID(), Timestamp.from(Instant.now()));
        return id;
    }
}
