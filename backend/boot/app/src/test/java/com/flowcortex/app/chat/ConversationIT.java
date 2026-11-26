package com.flowcortex.app.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.AbstractPostgresBootTest;
import com.flowcortex.user.api.UserService;
import com.flowcortex.user.api.UserView;
import java.net.URI;
import java.sql.Timestamp;
import java.text.Normalizer;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/** The conversation list, create, rename, delete and message read over HTTP, owner-scoped. */
@ChatIt
class ConversationIT extends AbstractPostgresBootTest {

    private static final URI PROBLEMS = URI.create("https://flow-cortex.dev/problems/");
    private static final String BASE = "/api/v1/chat/conversations";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserService userService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UserView admin;

    record LoginRequest(String email, String password) {
    }

    record TokenResponse(String accessToken) {
    }

    record TitleRequest(String title) {
    }

    record Item(UUID id, String title, Instant lastActivityAt, Instant createdAt, boolean pinned, UUID folderId,
            String preview, Boolean previewTruncated) {
    }

    record ListResponse(List<Item> items, String nextCursor, boolean chatEnabled) {
    }

    record Counts(int total, int pinned, int today, int last7Days, int earlier) {
    }

    record PinRequest(Boolean pinned) {
    }

    record MessageView(UUID id, int seq, String role, String kind, String body, String status) {
    }

    record Problem(int status, URI type, String detail, String code, Map<String, String> errors) {
    }

    @BeforeEach
    void setUp() {
        admin = userService.createInitialAdmin(uniqueEmail("chat-admin"), "password123", "Chat Admin");
        jdbcTemplate.update("UPDATE chat_settings SET enabled = true WHERE id = 1");
    }

    @Test
    void theListIsTheCallersOwnNewestActivityFirstWithIdDescendingOnATie() {
        UserView owner = member("chat-list");
        UserView other = member("chat-list-other");
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        UUID oldest = conversation(owner.id(), "Oldest", now.minusSeconds(300));
        UUID tieA = conversation(owner.id(), "Tie A", now);
        UUID tieB = conversation(owner.id(), "Tie B", now);
        UUID newest = conversation(owner.id(), "Newest", now.plusSeconds(60));
        conversation(other.id(), "Not mine", now.plusSeconds(120));

        ResponseEntity<ListResponse> response = restTemplate.exchange(BASE, HttpMethod.GET,
                new HttpEntity<>(bearer(login(owner.email()))), ListResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().chatEnabled()).isTrue();
        // Postgres orders uuid by unsigned bytes, which matches the canonical text; UUID.compareTo is signed.
        List<UUID> tied = List.of(tieA, tieB).stream()
                .sorted(java.util.Comparator.comparing(UUID::toString).reversed()).toList();
        assertThat(response.getBody().items()).extracting(Item::id)
                .containsExactly(newest, tied.get(0), tied.get(1), oldest);
        assertThat(response.getBody().items().getFirst().title()).isEqualTo("Newest");
        assertThat(response.getBody().items().getFirst().createdAt()).isNotNull();
    }

    @Test
    void theListReportsTheConsoleSwitchAndStaysReadableWhenOff() {
        UserView owner = member("chat-switch");
        conversation(owner.id(), "Kept", Instant.now());
        jdbcTemplate.update("UPDATE chat_settings SET enabled = false WHERE id = 1");

        ListResponse body = restTemplate.exchange(BASE, HttpMethod.GET,
                new HttpEntity<>(bearer(login(owner.email()))), ListResponse.class).getBody();

        assertThat(body.chatEnabled()).isFalse();
        assertThat(body.items()).hasSize(1);
    }

    @Test
    void aConversationIsOnlyEverCreatedWithAFirstMessage() {
        UserView owner = member("chat-create");
        HttpHeaders keyed = bearer(login(owner.email()));
        keyed.set("Idempotency-Key", UUID.randomUUID().toString());

        ResponseEntity<Problem> noBody = restTemplate.exchange(BASE, HttpMethod.POST, new HttpEntity<>(keyed),
                Problem.class);
        ResponseEntity<Problem> noMessage = restTemplate.exchange(BASE, HttpMethod.POST,
                new HttpEntity<>(new TitleRequest(null), keyed), Problem.class);
        ResponseEntity<Problem> titleOnly = restTemplate.exchange(BASE, HttpMethod.POST,
                new HttpEntity<>(new TitleRequest("Kịch bản"), keyed), Problem.class);

        for (ResponseEntity<Problem> refused : List.of(noBody, noMessage, titleOnly)) {
            assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(refused.getBody().type()).isEqualTo(PROBLEMS.resolve("validation-failed"));
            assertThat(refused.getBody().errors()).containsKey("text");
        }
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM chat_conversation WHERE owner_id = ?",
                Integer.class, owner.id())).isZero();
    }

    @Test
    void renamingAcceptsOneToEightyCharactersAfterTrimAndRefusesTheRest() {
        UserView owner = member("chat-rename");
        String token = login(owner.email());
        UUID id = conversation(owner.id(), "Before", Instant.now());

        ResponseEntity<Item> renamed = rename(token, id, "  " + "a".repeat(80) + "  ");
        ResponseEntity<Problem> tooLong = restTemplate.exchange(BASE + "/" + id, HttpMethod.PUT,
                new HttpEntity<>(new TitleRequest("a".repeat(81)), bearer(token)), Problem.class);
        ResponseEntity<Problem> blank = restTemplate.exchange(BASE + "/" + id, HttpMethod.PUT,
                new HttpEntity<>(new TitleRequest("   "), bearer(token)), Problem.class);

        assertThat(renamed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(renamed.getBody().title()).isEqualTo("a".repeat(80));
        assertThat(tooLong.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(tooLong.getBody().type()).isEqualTo(PROBLEMS.resolve("validation-failed"));
        assertThat(tooLong.getBody().errors()).containsKey("title");
        assertThat(blank.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(blank.getBody().errors()).containsKey("title");
        assertThat(jdbcTemplate.queryForObject("SELECT title FROM chat_conversation WHERE id = ?", String.class, id))
                .isEqualTo("a".repeat(80));
    }

    @Test
    void deletingRemovesTheMessagesAndLeavesTheRunsTheConversationStarted() {
        UserView owner = member("chat-delete");
        String token = login(owner.email());
        UUID id = conversation(owner.id(), "Doomed", Instant.now());
        message(id, 1, "user", "hello");
        UUID runId = run(owner.id());

        ResponseEntity<Void> response = restTemplate.exchange(BASE + "/" + id, HttpMethod.DELETE,
                new HttpEntity<>(bearer(token)), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM chat_conversation WHERE id = ?",
                Integer.class, id)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM chat_message WHERE conversation_id = ?",
                Integer.class, id)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM workflow_run WHERE id = ?",
                Integer.class, runId)).isEqualTo(1);
    }

    @Test
    void theMessagesComeInSequenceOrder() {
        UserView owner = member("chat-messages");
        UUID id = conversation(owner.id(), "Thread", Instant.now());
        message(id, 2, "assistant", "second");
        message(id, 1, "user", "first");

        ResponseEntity<MessageView[]> response = restTemplate.exchange(BASE + "/" + id + "/messages",
                HttpMethod.GET, new HttpEntity<>(bearer(login(owner.email()))), MessageView[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).extracting(MessageView::seq, MessageView::body)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(1, "first"),
                        org.assertj.core.groups.Tuple.tuple(2, "second"));
        assertThat(response.getBody()[0].role()).isEqualTo("user");
        assertThat(response.getBody()[0].kind()).isEqualTo("text");
        assertThat(response.getBody()[0].status()).isEqualTo("done");
    }

    @Test
    void anotherMembersConversationAndAMissingOneAreTheSame404() {
        UserView owner = member("chat-owner");
        UserView intruder = member("chat-intruder");
        String token = login(intruder.email());
        UUID foreign = conversation(owner.id(), "Private", Instant.now());
        message(foreign, 1, "user", "secret");
        UUID missing = UUID.randomUUID();

        for (UUID id : List.of(foreign, missing)) {
            assertNotFound(restTemplate.exchange(BASE + "/" + id + "/messages", HttpMethod.GET,
                    new HttpEntity<>(bearer(token)), Problem.class));
            assertNotFound(restTemplate.exchange(BASE + "/" + id, HttpMethod.PUT,
                    new HttpEntity<>(new TitleRequest("Mine now"), bearer(token)), Problem.class));
            assertNotFound(restTemplate.exchange(BASE + "/" + id, HttpMethod.DELETE,
                    new HttpEntity<>(bearer(token)), Problem.class));
        }
        assertThat(jdbcTemplate.queryForObject("SELECT title FROM chat_conversation WHERE id = ?",
                String.class, foreign)).isEqualTo("Private");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM chat_message WHERE conversation_id = ?",
                Integer.class, foreign)).isEqualTo(1);
    }

    @Test
    void theNotFoundDetailIsWrittenInTheRequestLanguage() {
        HttpHeaders english = bearer(login(member("chat-lang").email()));
        english.set(HttpHeaders.ACCEPT_LANGUAGE, "en");
        HttpHeaders vietnamese = bearer(login(member("chat-lang-vi").email()));
        vietnamese.set(HttpHeaders.ACCEPT_LANGUAGE, "vi");
        String path = BASE + "/" + UUID.randomUUID() + "/messages";

        Problem inEnglish = restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(english), Problem.class)
                .getBody();
        Problem inVietnamese = restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(vietnamese),
                Problem.class).getBody();

        assertThat(inEnglish.detail()).isEqualTo("No such conversation for this caller");
        assertThat(inVietnamese.detail()).isNotEqualTo(inEnglish.detail()).isNotBlank();
    }

    @Test
    void theRoutesNeedASignedInMember() {
        assertThat(restTemplate.getForEntity(BASE, String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(restTemplate.getForEntity(BASE + "/counts", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(restTemplate.exchange(BASE + "/" + UUID.randomUUID() + "/pin", HttpMethod.PUT,
                new HttpEntity<>(new PinRequest(true)), String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void sixtyFiveConversationsPageThirtyThirtyFiveEachOnceEvenWithEqualActivityTimes() {
        UserView owner = member("chat-paging");
        String token = login(owner.email());
        Instant base = Instant.now().truncatedTo(ChronoUnit.MILLIS).minus(2, ChronoUnit.DAYS);
        Set<UUID> created = new HashSet<>();
        for (int i = 0; i < 62; i++) {
            created.add(conversation(owner.id(), "C" + i, base.plusSeconds(i * 10L)));
        }
        Instant tied = base.plusSeconds(320);
        for (int i = 0; i < 3; i++) {
            created.add(conversation(owner.id(), "Tie " + i, tied));
        }

        ListResponse first = list(token, "limit", "30");
        ListResponse second = list(token, "limit", "30", "cursor", first.nextCursor());
        ListResponse third = list(token, "limit", "30", "cursor", second.nextCursor());

        assertThat(List.of(first.items().size(), second.items().size(), third.items().size()))
                .containsExactly(30, 30, 5);
        assertThat(first.nextCursor()).isNotBlank();
        assertThat(second.nextCursor()).isNotBlank();
        assertThat(third.nextCursor()).isNull();
        List<UUID> seen = Stream.of(first, second, third).flatMap(page -> page.items().stream()).map(Item::id)
                .toList();
        assertThat(seen).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(created);
        assertThat(first.items().getFirst().lastActivityAt())
                .isAfterOrEqualTo(third.items().getLast().lastActivityAt());
    }

    @Test
    void oldestFirstStartsWithTheOldestActivityAndItsCursorPagesOn() {
        UserView owner = member("chat-oldest");
        String token = login(owner.email());
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        UUID oldest = conversation(owner.id(), "Oldest", now.minusSeconds(900));
        UUID middle = conversation(owner.id(), "Middle", now.minusSeconds(600));
        UUID newest = conversation(owner.id(), "Newest", now.minusSeconds(300));

        ListResponse first = list(token, "sort", "oldest", "limit", "2");
        ListResponse second = list(token, "sort", "oldest", "limit", "2", "cursor", first.nextCursor());

        assertThat(first.items()).extracting(Item::id).containsExactly(oldest, middle);
        assertThat(second.items()).extracting(Item::id).containsExactly(newest);
        assertThat(second.nextCursor()).isNull();
    }

    @Test
    void aBadLimitCursorOrFilterIsRefusedAsValidationFailedOnItsField() {
        UserView owner = member("chat-refused");
        String token = login(owner.email());
        conversation(owner.id(), "One", Instant.now());
        conversation(owner.id(), "Two", Instant.now().minusSeconds(5));
        String newestCursor = list(token, "limit", "1").nextCursor();

        assertRefused(token, "limit", "limit", "0");
        assertRefused(token, "limit", "limit", "101");
        assertRefused(token, "cursor", "cursor", "abc");
        assertRefused(token, "cursor", "cursor", newestCursor, "sort", "oldest");
        assertRefused(token, "sort", "sort", "latest");
        assertRefused(token, "pinned", "pinned", "maybe");
        assertRefused(token, "folder", "folder", "abc");
        assertRefused(token, "q", "q", "q".repeat(101));
    }

    @Test
    void theFoldFunctionStripsVietnameseDiacriticsAndDecomposedMarks() {
        assertThat(fold("Quảng CÁO Đường")).isEqualTo("quang cao duong");
        assertThat(fold(Normalizer.normalize("Quảng", Normalizer.Form.NFD))).isEqualTo("quang");
        assertThat(fold("Ơ ư Ế ỷ ặ")).isEqualTo("o u e y a");
    }

    @Test
    void theRowPreviewIsTheLatestNonBlankTextMessageCutToTwoHundredCharacters() {
        UserView owner = member("chat-preview");
        String token = login(owner.email());
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        UUID longText = conversation(owner.id(), "Long", now);
        message(longText, 1, "user", "first");
        message(longText, 2, "assistant", "  \n" + "x".repeat(500));
        message(longText, 3, "assistant", "card", null);
        message(longText, 4, "assistant", "text", "   \n ");
        UUID cardsOnly = conversation(owner.id(), "Cards", now.minusSeconds(10));
        message(cardsOnly, 1, "assistant", "card", null);
        UUID empty = conversation(owner.id(), "Empty", now.minusSeconds(20));

        Map<UUID, Item> items = list(token).items().stream().collect(Collectors.toMap(Item::id, item -> item));

        assertThat(items.get(longText).preview()).isEqualTo("x".repeat(200));
        assertThat(items.get(longText).previewTruncated()).isTrue();
        assertThat(items.get(cardsOnly).preview()).isNull();
        assertThat(items.get(cardsOnly).previewTruncated()).isFalse();
        assertThat(items.get(empty).preview()).isNull();
        assertThat(items.get(empty).previewTruncated()).isFalse();
    }

    @Test
    void thePreviewIsTruncatedOnlyWhenTheTrimmedTextIsLongerThanTwoHundredCharacters() {
        UserView owner = member("chat-preview-edge");
        String token = login(owner.email());
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        UUID exact = conversation(owner.id(), "Exact", now);
        message(exact, 1, "assistant", "  \n" + "y".repeat(200) + "\n ");
        UUID over = conversation(owner.id(), "Over", now.minusSeconds(10));
        message(over, 1, "assistant", "y".repeat(201));

        Map<UUID, Item> items = list(token).items().stream().collect(Collectors.toMap(Item::id, item -> item));

        assertThat(items.get(exact).preview()).isEqualTo("y".repeat(200));
        assertThat(items.get(exact).previewTruncated()).isFalse();
        assertThat(items.get(over).preview()).isEqualTo("y".repeat(200));
        assertThat(items.get(over).previewTruncated()).isTrue();
    }

    @Test
    void searchIgnoresCaseAndDiacriticsAndNeedsEveryWordInTheTitleOrAMessage() {
        UserView owner = member("chat-search");
        String token = login(owner.email());
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        UUID titled = conversation(owner.id(), "Quảng cáo cà phê", now);
        UUID decomposed = conversation(owner.id(), "Untitled one", now.minusSeconds(1));
        message(decomposed, 1, "user", Normalizer.normalize("Quảng cáo", Normalizer.Form.NFD));
        UUID both = conversation(owner.id(), "Video notes", now.minusSeconds(2));
        message(both, 1, "assistant", "bản dịch hoàn chỉnh");
        UUID onlyVideo = conversation(owner.id(), "Video only", now.minusSeconds(3));
        UUID coffee = conversation(owner.id(), "Coffee", now.minusSeconds(4));
        message(coffee, 1, "user", "make a reel");

        assertThat(ids(list(token, "q", "QUANG CAO"))).containsExactlyInAnyOrder(titled, decomposed);
        assertThat(ids(list(token, "q", "quang"))).containsExactlyInAnyOrder(titled, decomposed);
        assertThat(ids(list(token, "q", "video dich"))).containsExactly(both);
        assertThat(ids(list(token, "q", "VIDEO"))).containsExactlyInAnyOrder(both, onlyVideo);
        assertThat(ids(list(token, "q", "coffee reel"))).containsExactly(coffee);
        assertThat(ids(list(token, "q", "coffee   REEL coffee"))).containsExactly(coffee);
        assertThat(ids(list(token, "q", "   "))).hasSize(5);
    }

    @Test
    void searchWordsMatchLiterallyAndNeverAsWildcards() {
        UserView owner = member("chat-literal");
        String token = login(owner.email());
        UUID plain = conversation(owner.id(), "Plain title", Instant.now());
        message(plain, 1, "user", "nothing special");
        UUID percent = conversation(owner.id(), "Sale 50% off", Instant.now().minusSeconds(1));
        UUID underscore = conversation(owner.id(), "snake_case", Instant.now().minusSeconds(2));
        UUID backslash = conversation(owner.id(), "C:\\temp", Instant.now().minusSeconds(3));

        assertThat(ids(list(token, "q", "%"))).containsExactly(percent);
        assertThat(ids(list(token, "q", "_"))).containsExactly(underscore);
        assertThat(ids(list(token, "q", "\\"))).containsExactly(backslash);
        assertThat(ids(list(token, "q", "50%"))).containsExactly(percent);
        assertThat(ids(list(token, "q", "p_ain"))).isEmpty();
        assertThat(ids(list(token, "q", "zzz%"))).isEmpty();
        assertThat(ids(list(token, "q", "plain"))).containsExactly(plain);
    }

    @Test
    void pinnedAndFolderFiltersSplitRecentPinnedAndFiledConversations() {
        UserView owner = member("chat-filters");
        UserView other = member("chat-filters-other");
        String token = login(owner.email());
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        UUID pinned = conversation(owner.id(), "Pinned", now);
        jdbcTemplate.update("UPDATE chat_conversation SET pinned = true WHERE id = ?", pinned);
        UUID filed = conversation(owner.id(), "Filed", now.minusSeconds(1));
        UUID folder = folder(owner.id(), "Ads");
        jdbcTemplate.update("UPDATE chat_conversation SET folder_id = ? WHERE id = ?", folder, filed);
        UUID pinnedAndFiled = conversation(owner.id(), "Pinned and filed", now.minusSeconds(2));
        jdbcTemplate.update("UPDATE chat_conversation SET pinned = true, folder_id = ? WHERE id = ?", folder,
                pinnedAndFiled);
        UUID recent = conversation(owner.id(), "Recent", now.minusSeconds(3));
        UUID foreignFolder = folder(other.id(), "Theirs");
        jdbcTemplate.update("UPDATE chat_conversation SET folder_id = ? WHERE id = ?", foreignFolder,
                conversation(other.id(), "Theirs", now));

        assertThat(ids(list(token, "pinned", "exclude", "folder", "none"))).containsExactly(recent);
        assertThat(ids(list(token, "pinned", "only"))).containsExactly(pinned, pinnedAndFiled);
        assertThat(ids(list(token, "folder", folder.toString()))).containsExactly(filed, pinnedAndFiled);
        assertThat(ids(list(token, "folder", folder.toString(), "pinned", "exclude"))).containsExactly(filed);
        assertThat(ids(list(token))).hasSize(4);
        assertThat(list(token, "folder", foreignFolder.toString()).items()).isEmpty();
        assertThat(list(token, "folder", UUID.randomUUID().toString()).items()).isEmpty();
        Item row = list(token, "folder", folder.toString(), "pinned", "exclude").items().getFirst();
        assertThat(row.folderId()).isEqualTo(folder);
        assertThat(row.pinned()).isFalse();
    }

    @Test
    void countsSplitPinnedAndTheTimeGroupsAtTheBoundariesAndFollowTheSearch() {
        UserView owner = member("chat-counts");
        String token = login(owner.email());
        Instant todayStart = Instant.now().truncatedTo(ChronoUnit.HOURS);
        Instant weekStart = todayStart.minus(7, ChronoUnit.DAYS);
        UUID pinnedToday = conversation(owner.id(), "Pinned today", todayStart.plusSeconds(5));
        UUID pinnedOld = conversation(owner.id(), "Pinned old", weekStart.minus(30, ChronoUnit.DAYS));
        jdbcTemplate.update("UPDATE chat_conversation SET pinned = true WHERE id IN (?, ?)", pinnedToday, pinnedOld);
        conversation(owner.id(), "Coffee at the boundary", todayStart);
        conversation(owner.id(), "Today B", todayStart.plusSeconds(60));
        conversation(owner.id(), "Today C", todayStart.plusSeconds(120));
        conversation(owner.id(), "Coffee week start", weekStart);
        conversation(owner.id(), "Week B", weekStart.plusSeconds(1));
        conversation(owner.id(), "Week C", todayStart.minusSeconds(1));
        conversation(owner.id(), "Week D", todayStart.minus(2, ChronoUnit.DAYS));
        for (int i = 1; i <= 5; i++) {
            conversation(owner.id(), "Earlier " + i, weekStart.minusSeconds(i));
        }

        Counts all = counts(token, todayStart, weekStart);
        Counts coffee = counts(token, todayStart, weekStart, "q", "coffee");

        assertThat(all).isEqualTo(new Counts(14, 2, 3, 4, 5));
        assertThat(coffee.total()).isEqualTo(list(token, "q", "coffee", "limit", "100").items().size());
        assertThat(coffee).isEqualTo(new Counts(2, 0, 1, 1, 0));
    }

    @Test
    void countsRefuseBoundariesOutOfOrderMissingOrUnreadable() {
        String token = login(member("chat-counts-bad").email());
        Instant now = Instant.now();

        assertCountsRefused(token, "weekStart", "todayStart", now.minusSeconds(60).toString(), "weekStart",
                now.toString());
        assertCountsRefused(token, "todayStart", "weekStart", now.toString());
        assertCountsRefused(token, "todayStart", "todayStart", "yesterday", "weekStart", now.toString());
        assertCountsRefused(token, "folder", "todayStart", now.toString(), "weekStart", now.toString(), "folder",
                "abc");
        assertThat(counts(token, now, now)).isEqualTo(new Counts(0, 0, 0, 0, 0));
    }

    @Test
    void pinningKeepsTheActivityTimeAndTheSameValueTwiceStaysPinned() {
        UserView owner = member("chat-pin");
        String token = login(owner.email());
        Instant yesterday = Instant.now().truncatedTo(ChronoUnit.MILLIS).minus(1, ChronoUnit.DAYS);
        UUID id = conversation(owner.id(), "Pin me", yesterday);

        ResponseEntity<Item> first = pin(token, id, true);
        ResponseEntity<Item> second = pin(token, id, true);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody().pinned()).isTrue();
        assertThat(first.getBody().lastActivityAt()).isEqualTo(yesterday);
        assertThat(first.getBody().title()).isEqualTo("Pin me");
        assertThat(second.getBody().pinned()).isTrue();
        assertThat(pin(token, id, false).getBody().pinned()).isFalse();
        assertThat(jdbcTemplate.queryForObject("SELECT last_activity_at FROM chat_conversation WHERE id = ?",
                Timestamp.class, id).toInstant()).isEqualTo(yesterday);
        ResponseEntity<Problem> missing = restTemplate.exchange(BASE + "/" + id + "/pin", HttpMethod.PUT,
                new HttpEntity<>(new PinRequest(null), bearer(token)), Problem.class);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(missing.getBody().errors()).containsKey("pinned");
    }

    @Test
    void anotherMembersConversationCannotBePinnedAndStaysUnchanged() {
        UserView owner = member("chat-pin-owner");
        UserView intruder = member("chat-pin-intruder");
        String token = login(intruder.email());
        UUID foreign = conversation(owner.id(), "Private", Instant.now());

        for (UUID id : List.of(foreign, UUID.randomUUID())) {
            assertNotFound(restTemplate.exchange(BASE + "/" + id + "/pin", HttpMethod.PUT,
                    new HttpEntity<>(new PinRequest(true), bearer(token)), Problem.class));
        }
        assertThat(jdbcTemplate.queryForObject("SELECT pinned FROM chat_conversation WHERE id = ?", Boolean.class,
                foreign)).isFalse();
    }

    @Test
    void listCountsAndPinStillWorkWithTheConsoleSwitchOff() {
        UserView owner = member("chat-off");
        String token = login(owner.email());
        UUID id = conversation(owner.id(), "Kept", Instant.now());
        jdbcTemplate.update("UPDATE chat_settings SET enabled = false WHERE id = 1");

        ListResponse listed = list(token);
        ResponseEntity<Item> pinned = pin(token, id, true);
        Counts counts = counts(token, Instant.now().truncatedTo(ChronoUnit.DAYS),
                Instant.now().minus(8, ChronoUnit.DAYS));

        assertThat(listed.chatEnabled()).isFalse();
        assertThat(listed.items()).hasSize(1);
        assertThat(pinned.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(pinned.getBody().pinned()).isTrue();
        assertThat(counts.total()).isEqualTo(1);
    }

    private ListResponse list(String token, String... params) {
        return get(token, BASE, ListResponse.class, params).getBody();
    }

    private Counts counts(String token, Instant todayStart, Instant weekStart, String... extra) {
        List<String> params = new ArrayList<>(List.of("todayStart", todayStart.toString(), "weekStart",
                weekStart.toString()));
        params.addAll(List.of(extra));
        return get(token, BASE + "/counts", Counts.class, params.toArray(String[]::new)).getBody();
    }

    private <T> ResponseEntity<T> get(String token, String path, Class<T> type, String... params) {
        UriComponentsBuilder uri = UriComponentsBuilder.fromPath(path);
        Map<String, String> variables = new HashMap<>();
        for (int i = 0; i < params.length; i += 2) {
            uri.queryParam(params[i], "{" + params[i] + "}");
            variables.put(params[i], params[i + 1]);
        }
        return restTemplate.exchange(uri.build().toUriString(), HttpMethod.GET, new HttpEntity<>(bearer(token)), type,
                variables);
    }

    private void assertRefused(String token, String field, String... params) {
        ResponseEntity<Problem> response = get(token, BASE, Problem.class, params);

        assertThat(response.getStatusCode()).as(field).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().type()).isEqualTo(PROBLEMS.resolve("validation-failed"));
        assertThat(response.getBody().errors()).containsOnlyKeys(field);
    }

    private void assertCountsRefused(String token, String field, String... params) {
        ResponseEntity<Problem> response = get(token, BASE + "/counts", Problem.class, params);

        assertThat(response.getStatusCode()).as(field).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().type()).isEqualTo(PROBLEMS.resolve("validation-failed"));
        assertThat(response.getBody().errors()).containsKey(field);
    }

    private ResponseEntity<Item> pin(String token, UUID id, boolean pinned) {
        return restTemplate.exchange(BASE + "/" + id + "/pin", HttpMethod.PUT,
                new HttpEntity<>(new PinRequest(pinned), bearer(token)), Item.class);
    }

    private String fold(String text) {
        return jdbcTemplate.queryForObject("SELECT chat_search_fold(?)", String.class, text);
    }

    private static List<UUID> ids(ListResponse response) {
        return response.items().stream().map(Item::id).toList();
    }

    private UUID folder(UUID ownerId, String name) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO chat_folder (id, owner_id, name) VALUES (?, ?, ?)", id, ownerId, name);
        return id;
    }

    private void assertNotFound(ResponseEntity<Problem> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().type()).isEqualTo(PROBLEMS.resolve("chat-conversation-not-found"));
        assertThat(response.getBody().code()).isEqualTo("chat-conversation-not-found");
    }

    private ResponseEntity<Item> rename(String token, UUID id, String title) {
        return restTemplate.exchange(BASE + "/" + id, HttpMethod.PUT,
                new HttpEntity<>(new TitleRequest(title), bearer(token)), Item.class);
    }

    private UUID conversation(UUID ownerId, String title, Instant lastActivity) {
        UUID id = UUID.randomUUID();
        Timestamp at = Timestamp.from(lastActivity);
        jdbcTemplate.update("INSERT INTO chat_conversation (id, owner_id, title, created_at, updated_at, "
                + "last_activity_at) VALUES (?, ?, ?, ?, ?, ?)", id, ownerId, title, at, at, at);
        return id;
    }

    private void message(UUID conversationId, int seq, String role, String body) {
        message(conversationId, seq, role, "text", body);
    }

    private void message(UUID conversationId, int seq, String role, String kind, String body) {
        jdbcTemplate.update("INSERT INTO chat_message (id, conversation_id, seq, role, kind, body, status) "
                + "VALUES (?, ?, ?, ?, ?, ?, 'done')", UUID.randomUUID(), conversationId, seq, role, kind, body);
    }

    private UUID run(UUID ownerId) {
        Timestamp now = Timestamp.from(Instant.now());
        UUID workflowId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO workflow (id, owner_id, name, is_shared, created_at, updated_at)"
                + " VALUES (?, ?, ?, false, ?, ?)", workflowId, ownerId, "Chat IT", now, now);
        UUID runId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO workflow_run (id, workflow_id, owner_id, status, started_at)"
                + " VALUES (?, ?, ?, 'running', ?)", runId, workflowId, ownerId, now);
        return runId;
    }

    private UserView member(String prefix) {
        return userService.createUser(admin.id(), uniqueEmail(prefix), "password123", "Thành viên " + prefix,
                "MEMBER");
    }

    private String login(String email) {
        TokenResponse token = restTemplate.postForEntity("/api/v1/auth/login",
                new LoginRequest(email, "password123"), TokenResponse.class).getBody();
        assertThat(token).isNotNull();
        return token.accessToken();
    }

    private static HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    private static String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@example.com";
    }
}
