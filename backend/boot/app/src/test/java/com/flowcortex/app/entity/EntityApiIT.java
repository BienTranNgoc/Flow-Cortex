package com.flowcortex.app.entity;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.flowcortex.app.workflow.AbstractWorkflowHttpIT;
import com.flowcortex.space.api.SessionSpaces;
import com.flowcortex.user.api.UserView;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Entity create, list, read, edit, delete and suggestions over HTTP with spaces on and a real login: the field,
 * part, handle, tag and category rules, optimistic versions under a row lock, and the single 404 for everything
 * outside the caller's own live entities.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "flowcortex.spaces.enabled=true")
@AutoConfigureTestRestTemplate
class EntityApiIT extends AbstractWorkflowHttpIT {

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

    record Counts(long brand, long product, long character, long voice) {
    }

    record PageOfEntities(List<EntityView> items, int page, int size, long total, Counts counts) {
    }

    record ProblemView(int status, String detail, Map<String, String> errors) {
    }

    record TagCount(String tag, long count) {
    }

    record TagItems(List<TagCount> items) {
    }

    record LabelCount(String label, long count) {
    }

    record LabelItems(List<LabelCount> items) {
    }

    private record Member(UUID id, String token, UUID space) {
    }

    private Member member(String prefix) {
        UserView user = newUser(prefix);
        return new Member(user.id(), loginAs(user), sessionSpaces.sessionSpace(user.id()).spaceId());
    }

    @Test
    void anotherMembersEntityAndARandomIdAnswerTheSame404() {
        Member a = member("entity-a");
        Member b = member("entity-b");
        EntityView entity = create(a, Map.of("kind", "brand", "name", "Nodi"));

        ResponseEntity<String> foreign = send(HttpMethod.GET, ENTITIES + "/" + entity.id(), b, null, String.class);
        ResponseEntity<String> random = send(HttpMethod.GET, ENTITIES + "/" + UUID.randomUUID(), b, null, String.class);
        ResponseEntity<String> edit = send(HttpMethod.PATCH, ENTITIES + "/" + entity.id(), b,
                Map.of("baseVersion", 1, "name", "Stolen"), String.class);
        ResponseEntity<String> delete = send(HttpMethod.DELETE, ENTITIES + "/" + entity.id(), b, null, String.class);

        assertThat(foreign.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(random.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(withoutInstance(foreign.getBody())).isEqualTo(withoutInstance(random.getBody()));
        assertThat(edit.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(withoutInstance(edit.getBody())).isEqualTo(withoutInstance(random.getBody()));
        assertThat(delete.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        EntityView unchanged = get(a, entity.id());
        assertThat(unchanged.name()).isEqualTo("Nodi");
        assertThat(unchanged.version()).isEqualTo(1);
    }

    @Test
    void malformedIdAnswersTheSame404() {
        Member a = member("entity-malformed");

        ResponseEntity<String> malformed = send(HttpMethod.GET, ENTITIES + "/not-a-uuid", a, null, String.class);
        ResponseEntity<String> random = send(HttpMethod.GET, ENTITIES + "/" + UUID.randomUUID(), a, null, String.class);

        assertThat(malformed.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(withoutInstance(malformed.getBody())).isEqualTo(withoutInstance(random.getBody()));
        assertThat(withoutInstance(send(HttpMethod.PATCH, ENTITIES + "/not-a-uuid", a, Map.of("baseVersion", 1),
                String.class).getBody())).isEqualTo(withoutInstance(random.getBody()));
        assertThat(withoutInstance(send(HttpMethod.DELETE, ENTITIES + "/not-a-uuid", a, null, String.class).getBody()))
                .isEqualTo(withoutInstance(random.getBody()));
    }

    @Test
    void createGeneratesAVietnameseHandleAndVersionOne() {
        Member a = member("entity-create");

        ResponseEntity<EntityView> created = send(HttpMethod.POST, ENTITIES, a,
                Map.of("kind", "product", "name", "  Áo thun cotton trắng "), EntityView.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        EntityView entity = created.getBody();
        assertThat(entity.handle()).isEqualTo("ao-thun-cotton-trang");
        assertThat(entity.name()).isEqualTo("Áo thun cotton trắng");
        assertThat(entity.kind()).isEqualTo("product");
        assertThat(entity.version()).isEqualTo(1);
        assertThat(created.getHeaders().getLocation()).hasPath(ENTITIES + "/" + entity.id());
        assertThat(jdbc.queryForObject("SELECT changes::text FROM space_entity_version WHERE entity_id = ? AND version = 1",
                String.class, entity.id())).contains("\"code\": \"created\"");
        assertThat(get(a, entity.id())).isEqualTo(entity);
    }

    @Test
    void secondSameNameGetsMinusTwo() {
        Member a = member("entity-suffix");

        EntityView first = create(a, Map.of("kind", "product", "name", "Áo thun cotton trắng"));
        EntityView second = create(a, Map.of("kind", "brand", "name", "Áo thun cotton trắng"));

        assertThat(first.handle()).isEqualTo("ao-thun-cotton-trang");
        assertThat(second.handle()).isEqualTo("ao-thun-cotton-trang-2");
        assertThat(create(a, Map.of("kind", "brand", "name", "★★★")).handle()).isEqualTo("thuc-the");
    }

    @Test
    void givenTakenHandleIs409() {
        Member a = member("entity-taken");
        create(a, Map.of("kind", "brand", "name", "Nodi", "handle", "nodi"));

        ResponseEntity<ProblemView> taken = send(HttpMethod.POST, ENTITIES, a,
                Map.of("kind", "brand", "name", "Other", "handle", "nodi"), ProblemView.class);
        ResponseEntity<ProblemView> capitals = send(HttpMethod.POST, ENTITIES, a,
                Map.of("kind", "brand", "name", "Other", "handle", "Nodi"), ProblemView.class);

        assertThat(taken.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(capitals.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(capitals.getBody().errors()).containsKey("handle");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM space_entity WHERE space_id = ?", Long.class, a.space()))
                .isEqualTo(1);
    }

    @Test
    void theHandleTakenSentenceFollowsTheRequestLanguage() {
        Member a = member("entity-vi");
        create(a, Map.of("kind", "brand", "name", "Nodi", "handle", "nodi"));
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(a.token());
        headers.set(HttpHeaders.ACCEPT_LANGUAGE, "vi");

        ResponseEntity<ProblemView> taken = restTemplate.exchange(ENTITIES, HttpMethod.POST,
                new HttpEntity<>(Map.of("kind", "brand", "name", "Other", "handle", "nodi"), headers),
                ProblemView.class);

        assertThat(taken.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(taken.getBody().detail())
                .isEqualTo(messages.getMessage("entity.handleTaken", null, Locale.forLanguageTag("vi")))
                .isNotEqualTo(messages.getMessage("entity.handleTaken", null, Locale.ENGLISH));
    }

    @Test
    void createRefusesWhatTheKindAndNameRulesRefuse() {
        Member a = member("entity-names");

        assertThat(refusal(a, Map.of("kind", "location", "name", "Shop")).errors()).containsKey("kind");
        assertThat(refusal(a, Map.of("kind", "brand", "name", "   ")).errors()).containsKey("name");
        assertThat(refusal(a, Map.of("kind", "brand", "name", "x".repeat(201))).errors()).containsKey("name");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM space_entity WHERE space_id = ?", Long.class, a.space()))
                .isZero();
    }

    @Test
    void fieldsBelongToTheirKindAndReplaceTheWholeObject() {
        Member a = member("entity-fields");

        assertThat(refusal(a, Map.of("kind", "character", "name", "Mai", "fields", Map.of("price", "199000")))
                .errors()).containsKey("fields");
        assertThat(refusal(a, Map.of("kind", "character", "name", "Mai", "fields", Map.of("origin", "ai")))
                .errors()).containsKey("fields");
        assertThat(refusal(a, Map.of("kind", "brand", "name", "Nodi",
                "fields", Map.of("colors", List.of("#ff0000", "red")))).errors()).containsKey("fields");
        assertThat(create(a, Map.of("kind", "product", "name", "Long",
                "fields", Map.of("description", "x".repeat(4_000)))).version()).isEqualTo(1);
        assertThat(refusal(a, Map.of("kind", "product", "name", "Longer",
                "fields", Map.of("description", "x".repeat(4_001)))).errors()).containsKey("fields");

        EntityView brand = create(a, Map.of("kind", "brand", "name", "Nodi",
                "fields", Map.of("voice", "warm", "audience", "students")));
        EntityView edited = patch(a, brand.id(), Map.of("baseVersion", 1, "fields", Map.of("voice", "bold")));

        assertThat(edited.fields()).isEqualTo(Map.of("voice", "bold"));
        assertThat(edited.version()).isEqualTo(2);
    }

    @Test
    void partWithAnotherMembersAssetIs400Parts() {
        Member a = member("entity-parts-a");
        Member b = member("entity-parts-b");
        UUID foreign = seedAsset(a, "image");

        ProblemView refused = refusal(b, Map.of("kind", "product", "name", "Tee",
                "parts", List.of(Map.of("assetId", foreign, "role", "product_photo"))));

        assertThat(refused.errors()).containsKey("parts");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM space_entity WHERE space_id = ?", Long.class, b.space()))
                .isZero();
    }

    @Test
    void voiceSampleImageIsAKindMismatch() {
        Member a = member("entity-voice");
        UUID image = seedAsset(a, "image");
        UUID audio = seedAsset(a, "audio");

        ProblemView mismatch = refusal(a, Map.of("kind", "voice", "name", "Mai",
                "parts", List.of(Map.of("assetId", image, "role", "voice_sample"))));
        ProblemView role = refusal(a, Map.of("kind", "brand", "name", "Nodi",
                "parts", List.of(Map.of("assetId", image, "role", "portrait"))));

        assertThat(mismatch.errors()).containsKey("parts");
        assertThat(role.errors()).containsKey("parts");
        assertThat(create(a, Map.of("kind", "voice", "name", "Mai",
                "parts", List.of(Map.of("assetId", audio, "role", "voice_sample")))).parts()).hasSize(1);
    }

    @Test
    void partsKeepTheirOrderAndAreLimitedToTwenty() {
        Member a = member("entity-order");
        UUID front = seedAsset(a, "image");
        UUID back = seedAsset(a, "image");
        EntityView product = create(a, Map.of("kind", "product", "name", "Tee",
                "parts", List.of(Map.of("assetId", front, "role", "product_photo", "label", "Front"))));

        EntityView edited = patch(a, product.id(), Map.of("baseVersion", 1, "parts", List.of(
                Map.of("assetId", back, "role", "back"), Map.of("assetId", front, "role", "product_photo"))));

        assertThat(edited.parts()).extracting(PartView::sort).containsExactly(0, 1);
        assertThat(edited.parts()).extracting(PartView::role).containsExactly("back", "product_photo");
        assertThat(edited.parts().get(0).asset().id()).isEqualTo(back);
        assertThat(edited.parts().get(0).asset().kind()).isEqualTo("image");
        List<Map<String, Object>> twentyOne = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            twentyOne.add(Map.of("assetId", front, "role", "detail"));
        }
        assertThat(refusal(a, Map.of("kind", "product", "name", "Many", "parts", twentyOne)).errors())
                .containsKey("parts");
        assertThat(refusal(a, Map.of("kind", "product", "name", "Labelled", "parts", List.of(
                Map.of("assetId", front, "role", "detail", "label", "x".repeat(61))))).errors())
                .containsKey("parts");
    }

    @Test
    void listFiltersByQWithoutDiacriticsAndCountsIgnoreKind() {
        Member a = member("entity-list");
        create(a, Map.of("kind", "product", "name", "Áo thun cotton trắng"));
        create(a, Map.of("kind", "product", "name", "Quần jean"));
        EntityView brand = create(a, Map.of("kind", "brand", "name", "Nodi"));

        PageOfEntities byName = list(a, "?q=ao thun");
        PageOfEntities byKind = list(a, "?kind=brand");

        assertThat(byName.items()).extracting(EntityView::name).containsExactly("Áo thun cotton trắng");
        assertThat(byKind.items()).extracting(EntityView::id).containsExactly(brand.id());
        assertThat(byKind.total()).isEqualTo(1);
        assertThat(byKind.counts()).isEqualTo(new Counts(1, 2, 0, 0));
        assertThat(list(a, "?q=nodi&kind=product").counts()).isEqualTo(new Counts(1, 0, 0, 0));
        assertThat(list(a, "?q=ao-thun").total()).isEqualTo(1);
        assertThat(list(a, "?q=_").total()).isZero();
        assertThat(list(a, "").items()).extracting(EntityView::name)
                .containsExactly("Nodi", "Quần jean", "Áo thun cotton trắng");
    }

    @Test
    void listIsPagedAndRefusesWhatItCannotRead() {
        Member a = member("entity-page");
        for (int i = 0; i < 3; i++) {
            create(a, Map.of("kind", "brand", "name", "Brand " + i));
        }

        PageOfEntities second = list(a, "?page=1&size=2");

        assertThat(second.items()).hasSize(1);
        assertThat(second.total()).isEqualTo(3);
        assertThat(second.size()).isEqualTo(2);
        assertThat(send(HttpMethod.GET, ENTITIES + "?size=101", a, null, ProblemView.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(send(HttpMethod.GET, ENTITIES + "?page=-1", a, null, ProblemView.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        ResponseEntity<ProblemView> longQuery = send(HttpMethod.GET, ENTITIES + "?q=" + "x".repeat(101), a, null,
                ProblemView.class);
        assertThat(longQuery.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(longQuery.getBody().errors()).containsKey("q");
        assertThat(send(HttpMethod.GET, ENTITIES + "?kind=location", a, null, ProblemView.class).getBody().errors())
                .containsKey("kind");
    }

    @Test
    void tagTypedTwiceKeepsTheFirstLabelAndFiltersByKey() {
        Member a = member("entity-tags");
        EntityView first = create(a, Map.of("kind", "product", "name", "Tee", "tags", List.of("Mùa Hè")));
        EntityView second = create(a, Map.of("kind", "product", "name", "Hat", "tags", List.of("mùa hè")));

        assertThat(first.tags()).containsExactly("Mùa Hè");
        assertThat(second.tags()).containsExactly("Mùa Hè");
        assertThat(send(HttpMethod.GET, ENTITIES + "/tags", a, null, TagItems.class).getBody().items())
                .containsExactly(new TagCount("Mùa Hè", 2));
        assertThat(list(a, "?tag=mua he").items()).extracting(EntityView::id)
                .containsExactlyInAnyOrder(first.id(), second.id());
        assertThat(send(HttpMethod.GET, ENTITIES + "/tags?q=he", a, null, TagItems.class).getBody().items()).hasSize(1);
        assertThat(send(HttpMethod.GET, ENTITIES + "/tags?q=zzz", a, null, TagItems.class).getBody().items()).isEmpty();
        assertThat(send(HttpMethod.GET, ENTITIES + "/tags?limit=21", a, null, ProblemView.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void tagsAreRefusedWhenTooManyOrAUrlOrDataUri() {
        Member a = member("entity-tag-rules");
        List<String> twentyOne = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            twentyOne.add("tag" + i);
        }

        assertThat(refusal(a, Map.of("kind", "brand", "name", "Nodi", "tags", twentyOne)).errors())
                .containsKey("tags");
        assertThat(refusal(a, Map.of("kind", "brand", "name", "Nodi",
                "tags", List.of("data:image/png;base64,AAAA"))).errors()).containsKey("tags");
        assertThat(create(a, Map.of("kind", "brand", "name", "Nodi",
                "fields", Map.of("voice", "Metadata: cotton 100%"))).fields()).containsEntry("voice",
                "Metadata: cotton 100%");
    }

    @Test
    void categoriesCountAcrossSpellings() {
        Member a = member("entity-categories");
        create(a, Map.of("kind", "product", "name", "Tea", "categoryLabel", "Đồ uống"));
        create(a, Map.of("kind", "product", "name", "Milk", "categoryLabel", "Đồ uống"));
        create(a, Map.of("kind", "product", "name", "Beer", "categoryLabel", "do uong"));

        List<LabelCount> categories = send(HttpMethod.GET, ENTITIES + "/categories", a, null, LabelItems.class)
                .getBody().items();

        assertThat(categories).containsExactly(new LabelCount("Đồ uống", 3));
        assertThat(list(a, "?category=DO UONG").total()).isEqualTo(3);
        assertThat(refusal(a, Map.of("kind", "product", "name", "Wine", "categoryLabel", "x".repeat(41))).errors())
                .containsKey("categoryLabel");
    }

    @Test
    void labelsGroupByKeyForARole() {
        Member a = member("entity-labels");
        UUID image = seedAsset(a, "image");
        UUID detail = seedAsset(a, "image");
        create(a, Map.of("kind", "product", "name", "One", "parts", List.of(
                Map.of("assetId", image, "role", "product_photo", "label", "Mặt trước"))));
        create(a, Map.of("kind", "product", "name", "Two", "parts", List.of(
                Map.of("assetId", image, "role", "product_photo", "label", "Mặt sau"))));
        create(a, Map.of("kind", "product", "name", "Three", "parts", List.of(
                Map.of("assetId", detail, "role", "detail", "label", "Cận cảnh"))));
        create(a, Map.of("kind", "product", "name", "Four", "parts", List.of(
                Map.of("assetId", image, "role", "product_photo", "label", "mặt trước"))));

        List<LabelCount> forRole = send(HttpMethod.GET, ENTITIES + "/labels?role=product_photo", a, null,
                LabelItems.class).getBody().items();

        assertThat(forRole).containsExactly(new LabelCount("mặt trước", 2), new LabelCount("Mặt sau", 1));
        assertThat(send(HttpMethod.GET, ENTITIES + "/labels", a, null, LabelItems.class).getBody().items())
                .extracting(LabelCount::label).contains("Cận cảnh");
        assertThat(send(HttpMethod.GET, ENTITIES + "/labels?role=logo", a, null, LabelItems.class).getBody().items())
                .isEmpty();
        assertThat(send(HttpMethod.GET, ENTITIES + "/labels?role=nope", a, null, ProblemView.class).getBody().errors())
                .containsKey("role");
    }

    @Test
    void editWithoutChangeKeepsTheVersion() {
        Member a = member("entity-nochange");
        EntityView entity = create(a, Map.of("kind", "brand", "name", "Nodi"));

        EntityView same = patch(a, entity.id(), Map.of("baseVersion", 1, "name", "  Nodi "));

        assertThat(same.version()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM space_entity_version WHERE entity_id = ?", Long.class,
                entity.id())).isEqualTo(1);
    }

    @Test
    void nullCategoryClearsAndAbsentKeeps() {
        Member a = member("entity-category-null");
        EntityView entity = create(a, Map.of("kind", "product", "name", "Tea", "categoryLabel", "Đồ uống"));

        EntityView renamed = patchRaw(a, entity.id(), "{\"baseVersion\":1,\"name\":\"Green tea\"}");
        EntityView cleared = patchRaw(a, entity.id(), "{\"baseVersion\":2,\"categoryLabel\":null}");

        assertThat(renamed.categoryLabel()).isEqualTo("Đồ uống");
        assertThat(cleared.categoryLabel()).isNull();
        assertThat(cleared.version()).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT changes::text FROM space_entity_version WHERE entity_id = ? AND version = 3",
                String.class, entity.id())).contains("categoryChanged");
        assertThat(jdbc.queryForObject("SELECT category_key FROM space_entity WHERE id = ?", String.class,
                entity.id())).isNull();
    }

    @Test
    void renameAndRetagWritesVersionTwoWithBothCodes() {
        Member a = member("entity-retag");
        EntityView entity = create(a, Map.of("kind", "product", "name", "Tee"));

        EntityView edited = patch(a, entity.id(), Map.of("baseVersion", 1, "name", "Tee v2", "tags", List.of("Sale")));

        assertThat(edited.version()).isEqualTo(2);
        assertThat(edited.tags()).containsExactly("Sale");
        String changes = jdbc.queryForObject("SELECT changes::text FROM space_entity_version "
                + "WHERE entity_id = ? AND version = 2", String.class, entity.id());
        assertThat(changes).contains("renamed", "tagsChanged", "Sale");
        assertThat(jdbc.queryForObject("SELECT snapshot->>'name' FROM space_entity_version "
                + "WHERE entity_id = ? AND version = 2", String.class, entity.id())).isEqualTo("Tee v2");
    }

    @Test
    void anEditRefusesAKindChangeAndAMissingBaseVersion() {
        Member a = member("entity-edit-rules");
        EntityView entity = create(a, Map.of("kind", "product", "name", "Tee"));

        ResponseEntity<ProblemView> kind = send(HttpMethod.PATCH, ENTITIES + "/" + entity.id(), a,
                Map.of("baseVersion", 1, "kind", "brand"), ProblemView.class);
        ResponseEntity<ProblemView> noBase = send(HttpMethod.PATCH, ENTITIES + "/" + entity.id(), a,
                Map.of("name", "Tee 2"), ProblemView.class);
        ResponseEntity<ProblemView> badHandle = send(HttpMethod.PATCH, ENTITIES + "/" + entity.id(), a,
                Map.of("baseVersion", 1, "handle", "Tee Shirt"), ProblemView.class);

        assertThat(kind.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(kind.getBody().errors()).containsKey("kind");
        assertThat(noBase.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(noBase.getBody().errors()).containsKey("baseVersion");
        assertThat(badHandle.getBody().errors()).containsKey("handle");
        EntityView unchanged = get(a, entity.id());
        assertThat(unchanged.kind()).isEqualTo("product");
        assertThat(unchanged.version()).isEqualTo(1);
    }

    @Test
    void anEditCannotTakeAHandleAnotherLiveEntityHolds() {
        Member a = member("entity-edit-handle");
        create(a, Map.of("kind", "brand", "name", "Nodi", "handle", "nodi"));
        EntityView other = create(a, Map.of("kind", "brand", "name", "Other", "handle", "other"));

        ResponseEntity<ProblemView> taken = send(HttpMethod.PATCH, ENTITIES + "/" + other.id(), a,
                Map.of("baseVersion", 1, "handle", "nodi"), ProblemView.class);

        assertThat(taken.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(get(a, other.id()).handle()).isEqualTo("other");
        assertThat(patch(a, other.id(), Map.of("baseVersion", 1, "handle", "other-2")).version()).isEqualTo(2);
    }

    @Test
    void staleBaseVersionIs409AndChangesNothing() {
        Member a = member("entity-stale");
        EntityView entity = create(a, Map.of("kind", "brand", "name", "Nodi"));
        patch(a, entity.id(), Map.of("baseVersion", 1, "name", "Nodi 2"));

        ResponseEntity<ProblemView> stale = send(HttpMethod.PATCH, ENTITIES + "/" + entity.id(), a,
                Map.of("baseVersion", 1, "name", "Nodi 3"), ProblemView.class);

        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        EntityView current = get(a, entity.id());
        assertThat(current.name()).isEqualTo("Nodi 2");
        assertThat(current.version()).isEqualTo(2);
    }

    @Test
    void twoEditsFromTheSameBaseVersionOneWinsOne409() throws Exception {
        Member a = member("entity-race");
        EntityView entity = create(a, Map.of("kind", "brand", "name", "Nodi"));

        List<HttpStatus> statuses = race(
                () -> status(HttpMethod.PATCH, ENTITIES + "/" + entity.id(), a, Map.of("baseVersion", 1, "name", "Left")),
                () -> status(HttpMethod.PATCH, ENTITIES + "/" + entity.id(), a, Map.of("baseVersion", 1, "name", "Right")));

        assertThat(statuses).containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.CONFLICT);
        assertThat(get(a, entity.id()).version()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM space_entity_version WHERE entity_id = ?", Long.class,
                entity.id())).isEqualTo(2);
    }

    @Test
    void concurrentCreatesWithTheSameNameBothSucceed() throws Exception {
        Member a = member("entity-concurrent");

        for (int round = 0; round < 5; round++) {
            String name = "Nodi" + round;
            List<HttpStatus> statuses = race(
                    () -> status(HttpMethod.POST, ENTITIES, a, Map.of("kind", "brand", "name", name)),
                    () -> status(HttpMethod.POST, ENTITIES, a, Map.of("kind", "brand", "name", name)));

            assertThat(statuses).containsExactly(HttpStatus.CREATED, HttpStatus.CREATED);
            Set<String> handles = new HashSet<>(jdbc.queryForList(
                    "SELECT handle FROM space_entity WHERE space_id = ? AND name = ?", String.class, a.space(), name));
            assertThat(handles).containsExactlyInAnyOrder("nodi" + round, "nodi" + round + "-2");
        }
    }

    @Test
    void deleteHidesFromRoutesListsAndSuggestionsAndFreesTheHandle() {
        Member a = member("entity-delete");
        UUID image = seedAsset(a, "image");
        EntityView entity = create(a, Map.of("kind", "brand", "name", "Nodi", "handle", "nodi",
                "tags", List.of("Tết"), "categoryLabel", "Lễ",
                "parts", List.of(Map.of("assetId", image, "role", "logo", "label", "Logo chính"))));

        ResponseEntity<String> deleted = send(HttpMethod.DELETE, ENTITIES + "/" + entity.id(), a, null, String.class);

        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(send(HttpMethod.GET, ENTITIES + "/" + entity.id(), a, null, String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(send(HttpMethod.DELETE, ENTITIES + "/" + entity.id(), a, null, String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(send(HttpMethod.PATCH, ENTITIES + "/" + entity.id(), a, Map.of("baseVersion", 1, "name", "Back"),
                String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        PageOfEntities page = list(a, "");
        assertThat(page.items()).isEmpty();
        assertThat(page.counts()).isEqualTo(new Counts(0, 0, 0, 0));
        assertThat(send(HttpMethod.GET, ENTITIES + "/tags", a, null, TagItems.class).getBody().items()).isEmpty();
        assertThat(send(HttpMethod.GET, ENTITIES + "/categories", a, null, LabelItems.class).getBody().items())
                .isEmpty();
        assertThat(send(HttpMethod.GET, ENTITIES + "/labels", a, null, LabelItems.class).getBody().items()).isEmpty();
        assertThat(create(a, Map.of("kind", "brand", "name", "Nodi", "handle", "nodi")).handle()).isEqualTo("nodi");
        assertThat(jdbc.queryForObject("SELECT deleted_by FROM space_entity WHERE id = ?", UUID.class, entity.id()))
                .isEqualTo(a.id());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM space_entity_version WHERE entity_id = ?", Long.class,
                entity.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM space_entity_part WHERE entity_id = ?", Long.class,
                entity.id())).isEqualTo(1);
    }

    @Test
    void signedUrlInAFieldIs400AndNotLogged() {
        Member a = member("entity-signed");
        ListAppender<ILoggingEvent> lines = new ListAppender<>();
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        lines.start();
        root.addAppender(lines);
        try {
            ResponseEntity<ProblemView> refused = send(HttpMethod.POST, ENTITIES, a,
                    Map.of("kind", "product", "name", "Tee", "fields", Map.of("salesUrl",
                            "https://shop.example/p?X-Amz-Signature=SECRETSIGNATURE123")), ProblemView.class);

            assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(refused.getBody().errors()).containsKey("fields");
            assertThat(refused.getBody().detail() + refused.getBody().errors()).doesNotContain("SECRETSIGNATURE123");
        } finally {
            root.detachAppender(lines);
        }
        assertThat(lines.list).noneMatch(event -> event.getFormattedMessage().contains("SECRETSIGNATURE123")
                || String.valueOf(event.getThrowableProxy()).contains("SECRETSIGNATURE123"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM space_entity WHERE space_id = ?", Long.class, a.space()))
                .isZero();
    }

    @Test
    void textWithNulCharacterIsRefusedNotA500() {
        Member a = member("entity-nul");

        assertThat(refusal(a, Map.of("kind", "brand", "name", "No\u0000di")).errors()).containsKey("name");
        assertThat(refusal(a, Map.of("kind", "brand", "name", "Nodi", "fields", Map.of("voice", "a\u0000b")))
                .errors()).containsKey("fields");
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

    private ProblemView refusal(Member member, Map<String, Object> body) {
        ResponseEntity<ProblemView> refused = send(HttpMethod.POST, ENTITIES, member, body, ProblemView.class);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        return refused.getBody();
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

    private EntityView patchRaw(Member member, UUID id, String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(member.token());
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<EntityView> patched = restTemplate.exchange(ENTITIES + "/" + id, HttpMethod.PATCH,
                new HttpEntity<>(json, headers), EntityView.class);
        assertThat(patched.getStatusCode()).isEqualTo(HttpStatus.OK);
        return patched.getBody();
    }

    private PageOfEntities list(Member member, String query) {
        ResponseEntity<PageOfEntities> page = send(HttpMethod.GET, ENTITIES + query, member, null,
                PageOfEntities.class);
        assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
        return page.getBody();
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
