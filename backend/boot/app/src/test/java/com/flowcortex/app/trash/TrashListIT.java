package com.flowcortex.app.trash;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.AbstractPostgresBootTest;
import com.flowcortex.trash.api.TrashBin;
import com.flowcortex.trash.api.TrashRecord;
import com.flowcortex.user.api.UserService;
import com.flowcortex.user.api.UserView;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code GET /api/v1/trash} over HTTP with real logins and a real Postgres. Entries are recorded through the port
 * with the settable clock at their delete time; the clock stays within minutes of the real one, because it also
 * stamps the access tokens the logins return.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "flowcortex.trash.enabled=true")
@AutoConfigureTestRestTemplate
@Import(TrashTestProviders.class)
class TrashListIT extends AbstractPostgresBootTest {

    private static final String TRASH = "/api/v1/trash";
    private static final String LOCATION = TrashTestProviders.LOCATION;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserService userService;

    @Autowired
    private TrashBin bin;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private TrashTestProviders.SettableClock clock;

    private TransactionTemplate transaction;
    private Instant now;
    private UUID memberA;
    private UUID memberB;
    private String tokenA;
    private String tokenB;

    record Item(String type, String id, String name, String location, Instant deletedAt, Instant expiresAt,
            int remainingDays) {
    }

    record Page(List<Item> items, int page, int size, long total, Map<String, Long> counts) {
    }

    record Problem(int status, String detail, Map<String, String> errors) {
    }

    record LoginRequest(String email, String password) {
    }

    record Token(String accessToken) {
    }

    @BeforeEach
    void setUp() {
        transaction = new TransactionTemplate(transactionManager);
        now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        clock.set(now);
        jdbc.update("DELETE FROM trash_entry");
        UserView a = newMember("trash-a");
        UserView b = newMember("trash-b");
        memberA = a.id();
        memberB = b.id();
        tokenA = login(a);
        tokenB = login(b);
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM trash_entry");
    }

    @Test
    void listsOnlyTheCallersEntriesOfProvidedTypes() {
        record(memberA, "demo", "p1", "Bản nháp", now);
        record(memberA, "demo_late", "p2", "Bản nháp cũ", now);
        record(memberA, "retired", "p3", "Đã gỡ", now);
        record(memberB, "demo", "p4", "Của người khác", now);

        Page page = list(tokenA, null, TRASH);

        assertThat(page.items()).extracting(Item::id).containsExactlyInAnyOrder("p1", "p2");
        assertThat(page.total()).isEqualTo(2);
        assertThat(page.counts()).containsExactlyInAnyOrderEntriesOf(Map.of("demo", 1L, "demo_late", 1L, "all", 2L));
    }

    @Test
    void typeAndSearchFilter() {
        record(memberA, "demo", "p1", "Bản nháp", now);
        record(memberA, "demo", "p2", "Kịch bản", now);
        record(memberA, "demo", "p3", "Ảnh bìa", now);
        record(memberA, "demo_late", "p4", "Bản nháp cũ", now);

        Page page = list(tokenA, null, TRASH + "?type={t}&q={q}", "demo", "ban nhap");

        assertThat(page.items()).extracting(Item::id).containsExactly("p1");
        assertThat(page.items().getFirst().type()).isEqualTo("demo");
        assertThat(page.total()).isEqualTo(1);
    }

    @Test
    void aSearchTextWithLikeWildcardsMatchesThemLiterally() {
        record(memberA, "demo", "p1", "100% done", now);
        record(memberA, "demo", "p2", "1000 done", now);
        record(memberA, "demo", "p3", "a_b", now);
        record(memberA, "demo", "p4", "axb", now);

        assertThat(list(tokenA, null, TRASH + "?q={q}", "100%").items()).extracting(Item::id)
                .containsExactly("p1");
        assertThat(list(tokenA, null, TRASH + "?q={q}", "a_b").items()).extracting(Item::id)
                .containsExactly("p3");
    }

    @Test
    void recentIsTheDefaultAndExpiringReversesIt() {
        record(memberA, "demo", "oldest", "x", now.minus(Duration.ofDays(3)));
        record(memberA, "demo", "tie1", "y", now.minus(Duration.ofDays(2)));
        record(memberA, "demo", "tie2", "z", now.minus(Duration.ofDays(2)));
        record(memberA, "demo", "newest", "w", now.minus(Duration.ofDays(1)));
        List<String> tiesById = jdbc.queryForList(
                "SELECT item_id FROM trash_entry WHERE item_id LIKE 'tie%' ORDER BY id", String.class);

        List<String> recent = ids(list(tokenA, null, TRASH));
        List<String> recentExplicit = ids(list(tokenA, null, TRASH + "?sort=recent"));
        List<String> expiring = ids(list(tokenA, null, TRASH + "?sort=expiring"));

        assertThat(recent).containsExactly("newest", tiesById.get(0), tiesById.get(1), "oldest");
        assertThat(recentExplicit).isEqualTo(recent);
        assertThat(expiring).containsExactly("oldest", tiesById.get(0), tiesById.get(1), "newest");
    }

    @Test
    void pageAndSizeSliceTheList() {
        for (int i = 1; i <= 5; i++) {
            record(memberA, "demo", "p" + i, "n" + i, now.minus(Duration.ofHours(i)));
        }

        Page first = list(tokenA, null, TRASH + "?size=2");
        Page last = list(tokenA, null, TRASH + "?size=2&page=2");
        Page beyond = list(tokenA, null, TRASH + "?size=2&page=3");

        assertThat(ids(first)).containsExactly("p1", "p2");
        assertThat(first.page()).isZero();
        assertThat(first.size()).isEqualTo(2);
        assertThat(first.total()).isEqualTo(5);
        assertThat(ids(last)).containsExactly("p5");
        assertThat(last.page()).isEqualTo(2);
        assertThat(beyond.items()).isEmpty();
        assertThat(beyond.total()).isEqualTo(5);
    }

    @Test
    void countsIgnoreTypeQueryAndPaging() {
        record(memberA, "demo", "p1", "Bản nháp", now);
        record(memberA, "demo", "p2", "Kịch bản", now);
        record(memberA, "demo", "p3", "Ảnh bìa", now);
        record(memberA, "demo_late", "p4", "Bản nháp cũ", now);
        for (int i = 1; i <= 5; i++) {
            record(memberB, "demo", "b" + i, "Của B", now);
        }

        Page filtered = list(tokenA, null, TRASH + "?type=demo&q=zzz&size=1");
        Page onlyDemo = list(tokenB, null, TRASH);

        assertThat(filtered.total()).isZero();
        assertThat(filtered.items()).isEmpty();
        assertThat(filtered.counts()).containsExactlyInAnyOrderEntriesOf(
                Map.of("demo", 3L, "demo_late", 1L, "all", 4L));
        assertThat(onlyDemo.counts()).containsExactlyInAnyOrderEntriesOf(
                Map.of("demo", 5L, "demo_late", 0L, "all", 5L));
    }

    @Test
    void pagingBounds() {
        record(memberA, "demo", "p1", "Bản nháp", now);

        assertNamesField(TRASH + "?size=51", "size");
        assertNamesField(TRASH + "?size=0", "size");
        assertNamesField(TRASH + "?size=many", "size");
        assertNamesField(TRASH + "?page=-1", "page");
        assertNamesField(TRASH + "?page=first", "page");
        assertNamesField(TRASH + "?q=" + "a".repeat(101), "q");
        assertNamesField(TRASH + "?type=Demo!", "type");
        assertNamesField(TRASH + "?sort=oldest", "sort");
        assertThat(list(tokenA, null, TRASH + "?q=" + "a".repeat(100)).items()).isEmpty();
        assertThat(list(tokenA, null, TRASH + "?size=50").items()).hasSize(1);
        assertThat(list(tokenA, null, TRASH + "?size=1&page=0").items()).hasSize(1);
    }

    @Test
    void aSearchOfMultiCodePointCharactersCountsCodePointsNotUnits() {
        String astral = "😀";

        assertThat(list(tokenA, null, TRASH + "?q={q}", astral.repeat(100)).items()).isEmpty();
        assertNamesField(TRASH + "?q=" + astral.repeat(101), "q");
    }

    @Test
    void wellFormedTypeWithoutProviderIsAnEmptyPage() {
        record(memberA, "retired", "p3", "Đã gỡ", now);
        record(memberA, "demo", "p1", "Bản nháp", now);

        Page page = list(tokenA, null, TRASH + "?type=retired");

        assertThat(page.items()).isEmpty();
        assertThat(page.total()).isZero();
        assertThat(page.counts()).containsExactlyInAnyOrderEntriesOf(Map.of("demo", 1L, "demo_late", 0L, "all", 1L));
    }

    @Test
    void expiresAtAndRemainingDays() {
        Instant almostExpired = now.minus(Duration.ofDays(29)).minus(Duration.ofHours(2));
        Instant pastExpiry = now.minus(Duration.ofDays(30)).minus(Duration.ofHours(1));
        Instant fresh = now;
        record(memberA, "demo", "almost", "a", almostExpired);
        record(memberA, "demo", "past", "b", pastExpiry);
        record(memberA, "demo", "fresh", "c", fresh);

        Map<String, Item> byId = new HashMap<>();
        list(tokenA, null, TRASH).items().forEach(item -> byId.put(item.id(), item));

        assertThat(byId.get("almost").deletedAt()).isEqualTo(almostExpired);
        assertThat(byId.get("almost").expiresAt()).isEqualTo(almostExpired.plus(Duration.ofDays(30)));
        assertThat(byId.get("almost").remainingDays()).isEqualTo(1);
        assertThat(byId.get("past").remainingDays()).isZero();
        assertThat(byId.get("past").expiresAt()).isEqualTo(pastExpiry.plus(Duration.ofDays(30)));
        assertThat(byId.get("fresh").remainingDays()).isEqualTo(30);
    }

    @Test
    void locationRenderedInTheRequestLanguage() {
        record(memberA, "demo", "p1", "Bản nháp", now);

        assertThat(list(tokenA, "en", TRASH).items().getFirst().location()).isEqualTo("Demo folder");
        assertThat(list(tokenA, "vi", TRASH).items().getFirst().location()).isEqualTo("Thư mục demo");
    }

    @Test
    void unknownLocationCodeShowsTheCode() {
        record(memberA, "demo", "p1", "Bản nháp", "trash.location.missing", now);

        ResponseEntity<Page> response = get(tokenA, "vi", TRASH, Page.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().items().getFirst().location()).isEqualTo("trash.location.missing");
    }

    @Test
    void untitledItemShowsTheUntitledText() {
        record(memberA, "demo", "p1", null, now);

        Item english = list(tokenA, "en", TRASH).items().getFirst();
        Item vietnamese = list(tokenA, "vi", TRASH).items().getFirst();

        assertThat(english.name()).isEqualTo("Untitled");
        assertThat(vietnamese.name()).isEqualTo("Chưa đặt tên");
        assertThat(list(tokenA, null, TRASH + "?q=untitled").items()).isEmpty();
    }

    @Test
    void forgottenItemIsNotListedOrCounted() {
        record(memberA, "demo", "p1", "Bản nháp", now);
        record(memberA, "demo", "p2", "Kịch bản", now);
        transaction.executeWithoutResult(status -> bin.forget("demo", "p1"));

        Page page = list(tokenA, null, TRASH);

        assertThat(ids(page)).containsExactly("p2");
        assertThat(page.total()).isEqualTo(1);
        assertThat(page.counts()).containsEntry("demo", 1L).containsEntry("all", 1L);
    }

    private UserView newMember(String prefix) {
        return userService.createInitialAdmin(prefix + "-" + UUID.randomUUID() + "@example.com", "password123", prefix);
    }

    private String login(UserView member) {
        return restTemplate.postForEntity("/api/v1/auth/login", new LoginRequest(member.email(), "password123"),
                Token.class).getBody().accessToken();
    }

    private void record(UUID owner, String type, String itemId, String name, Instant deletedAt) {
        record(owner, type, itemId, name, LOCATION, deletedAt);
    }

    private void record(UUID owner, String type, String itemId, String name, String location, Instant deletedAt) {
        clock.set(deletedAt);
        transaction.executeWithoutResult(status ->
                bin.record(new TrashRecord(type, itemId, owner, owner, name, location)));
        clock.set(now);
    }

    private Page list(String token, String language, String url, Object... variables) {
        ResponseEntity<Page> response = get(token, language, url, Page.class, variables);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private <T> ResponseEntity<T> get(String token, String language, String url, Class<T> type,
            Object... variables) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        if (language != null) {
            headers.set(HttpHeaders.ACCEPT_LANGUAGE, language);
        }
        return restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), type, variables);
    }

    private void assertNamesField(String url, String field) {
        ResponseEntity<Problem> response = get(tokenA, "en", url, Problem.class);

        assertThat(response.getStatusCode()).as(url).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().errors()).as(url).containsOnlyKeys(field);
    }

    private static List<String> ids(Page page) {
        return page.items().stream().map(Item::id).toList();
    }
}
