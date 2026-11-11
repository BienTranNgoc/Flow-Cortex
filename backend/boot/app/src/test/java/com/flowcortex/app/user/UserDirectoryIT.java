package com.flowcortex.app.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.flowcortex.user.api.UserView;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/** {@code GET /api/v1/users} and {@code /api/v1/users/stats} against the real, fully-wired app. */
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class UserDirectoryIT extends AbstractUserModuleIT {

    private static final String PASSWORD = "correct-password-1";

    record Row(UUID id, String email, String name, String role, String status, String state, String username,
            Instant emailConfirmedAt, Instant lockedUntil, List<String> signInMethods, boolean adminDisabled,
            boolean staffTarget) {
    }

    record Page(List<Row> items, int page, int size, long total) {
    }

    record Stats(long total, long active, long pendingConfirmation, long locked, long suspended, long newLast7Days) {
    }

    @Autowired
    private JdbcTemplate jdbc;

    private UserView seedAdmin(String label) {
        return userService.createInitialAdmin(uniqueEmail(label), PASSWORD, label);
    }

    private UserView seed(UserView admin, String name, String role) {
        return userService.createUser(admin.id(), uniqueEmail("dir"), PASSWORD, name, role);
    }

    private final Map<UUID, String> tokens = new ConcurrentHashMap<>();

    private String tokenOf(UserView user) {
        return tokens.computeIfAbsent(user.id(), id -> login(user.email(), PASSWORD));
    }

    private Page list(UserView admin, String query) {
        ResponseEntity<Page> response = get("/api/v1/users" + query, tokenOf(admin), Page.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private Set<UUID> ids(Page page) {
        return page.items().stream().map(Row::id).collect(Collectors.toSet());
    }

    private void set(UUID id, String column, Object value) {
        jdbc.update("UPDATE app_user SET " + column + " = ? WHERE id = ?", value, id);
    }

    private static Timestamp at(Instant instant) {
        return Timestamp.from(instant);
    }

    @Test
    void eachStateFilterReturnsExactlyTheRowsOfThatState() {
        UserView admin = seedAdmin("dir-states");
        Instant now = Instant.now();
        UserView pending = seed(admin, "Pending", "MEMBER");
        set(pending.id(), "email_confirmed_at", null);
        UserView locked = seed(admin, "Locked", "MEMBER");
        set(locked.id(), "locked_until", at(now.plus(Duration.ofMinutes(10))));
        UserView lockoutOver = seed(admin, "Lockout over", "MEMBER");
        set(lockoutOver.id(), "locked_until", at(now.minus(Duration.ofMinutes(1))));
        UserView suspended = seed(admin, "Suspended", "MEMBER");
        set(suspended.id(), "status", "SUSPENDED");
        set(suspended.id(), "email_confirmed_at", null);
        set(suspended.id(), "locked_until", at(now.plus(Duration.ofHours(1))));
        UserView active = seed(admin, "Active", "MEMBER");

        Map<String, Set<UUID>> expected = Map.of(
                "active", Set.of(admin.id(), active.id(), lockoutOver.id()),
                "pending_confirmation", Set.of(pending.id()),
                "locked", Set.of(locked.id()),
                "suspended", Set.of(suspended.id()));
        long sum = 0;
        for (var entry : expected.entrySet()) {
            Page page = list(admin, "?size=100&state=" + entry.getKey());
            assertThat(ids(page)).as(entry.getKey()).isEqualTo(entry.getValue());
            assertThat(page.items()).extracting(Row::state).containsOnly(entry.getKey());
            assertThat(page.total()).isEqualTo(entry.getValue().size());
            sum += page.total();
        }
        assertThat(sum).isEqualTo(list(admin, "?size=100").total());
        Row over = list(admin, "?size=100").items().stream().filter(r -> r.id().equals(lockoutOver.id()))
                .findFirst().orElseThrow();
        assertThat(over.lockedUntil()).isNull();
    }

    @Test
    void searchIgnoresCaseAndDiacriticsAcrossNameEmailAndUsername() {
        UserView admin = seedAdmin("dir-search");
        UserView duc = userService.createUser(admin.id(), "duc-" + UUID.randomUUID() + "@nodi.vn", PASSWORD,
                "Nguyễn Văn Đức", "MEMBER");
        set(duc.id(), "username", "ducnv");
        UserView other = seed(admin, "Someone Else", "MEMBER");

        assertThat(ids(list(admin, "?q=nguyen van duc"))).containsExactly(duc.id());
        assertThat(ids(list(admin, "?q=DUCNV"))).containsExactly(duc.id());
        assertThat(ids(list(admin, "?q=NODI.VN"))).containsExactly(duc.id());
        assertThat(ids(list(admin, "?q=  NGUYỄN "))).containsExactly(duc.id());
        assertThat(ids(list(admin, "?q=someone"))).containsExactly(other.id());
        assertThat(list(admin, "?q=%").total()).as("a percent sign is not a wildcard").isZero();
        assertThat(list(admin, "?q=_").total()).as("an underscore is not a wildcard").isZero();
        assertThat(list(admin, "?q=  ").total()).as("a blank q is no search").isEqualTo(3);
    }

    @Test
    void uppercaseVietnameseNameFoldsInTheDatabase() {
        UserView admin = seedAdmin("dir-upper");
        UserView duc = seed(admin, "ĐỨC NGUYỄN", "MEMBER");

        assertThat(ids(list(admin, "?q=duc nguyen"))).containsExactly(duc.id());
    }

    @Test
    void filtersCombine() {
        UserView admin = seedAdmin("dir-combine");
        UserView suspendedCreator = seed(admin, "Creator off", "CREATOR");
        set(suspendedCreator.id(), "status", "SUSPENDED");
        seed(admin, "Creator on", "CREATOR");
        UserView suspendedMember = seed(admin, "Member off", "MEMBER");
        set(suspendedMember.id(), "status", "SUSPENDED");

        Page page = list(admin, "?role=CREATOR&state=suspended");

        assertThat(ids(page)).containsExactly(suspendedCreator.id());
        assertThat(page.total()).isEqualTo(1);
        assertThat(ids(list(admin, "?role=CREATOR&state=suspended&q=member"))).isEmpty();
    }

    @Test
    void pagesAreNewestFirstAndTiesBreakById() {
        UserView admin = seedAdmin("dir-paging");
        Instant now = Instant.now();
        set(admin.id(), "created_at", at(now.minus(Duration.ofDays(10))));
        UserView tieA = seed(admin, "Tie A", "MEMBER");
        UserView tieB = seed(admin, "Tie B", "MEMBER");
        Timestamp sameInstant = at(now.minus(Duration.ofDays(1)));
        set(tieA.id(), "created_at", sameInstant);
        set(tieB.id(), "created_at", sameInstant);
        UserView newest = seed(admin, "Newest", "MEMBER");
        set(newest.id(), "created_at", at(now.minus(Duration.ofHours(2))));
        List<String> tieIdsAscending = List.of(tieA.id().toString(), tieB.id().toString()).stream().sorted().toList();

        assertThat(walk(admin, "newest")).containsExactly(newest.id().toString(),
                tieIdsAscending.get(1), tieIdsAscending.get(0), admin.id().toString());
        assertThat(walk(admin, "oldest")).containsExactly(admin.id().toString(),
                tieIdsAscending.get(0), tieIdsAscending.get(1), newest.id().toString());
    }

    private List<String> walk(UserView admin, String sort) {
        List<String> seen = new ArrayList<>();
        for (int page = 0; page < 4; page++) {
            Page current = list(admin, "?size=1&sort=" + sort + "&page=" + page);
            assertThat(current.total()).isEqualTo(4);
            assertThat(current.items()).hasSize(1);
            seen.add(current.items().getFirst().id().toString());
        }
        assertThat(list(admin, "?size=1&sort=" + sort + "&page=4").items()).isEmpty();
        assertThat(new HashSet<>(seen)).hasSize(4);
        return seen;
    }

    @Test
    void emailAndNameSortsAreCaseInsensitive() {
        UserView admin = seedAdmin("dir-sorts");
        UserView lower = userService.createUser(admin.id(), "b-" + UUID.randomUUID() + "@example.com", PASSWORD,
                "bravo", "MEMBER");
        UserView upper = userService.createUser(admin.id(), "C-" + UUID.randomUUID() + "@example.com", PASSWORD,
                "Charlie", "MEMBER");
        UserView first = userService.createUser(admin.id(), "a-" + UUID.randomUUID() + "@example.com", PASSWORD,
                "Alpha", "MEMBER");

        List<UUID> byEmail = list(admin, "?size=100&sort=email").items().stream().map(Row::id).toList();
        assertThat(byEmail).containsSubsequence(first.id(), lower.id(), upper.id());
        List<UUID> byName = list(admin, "?size=100&sort=name&q=a").items().stream().map(Row::id).toList();
        assertThat(byName).containsSubsequence(first.id(), lower.id(), upper.id());
    }

    @Test
    void anotherAdminsAccountsNeverAppear() {
        UserView an = seedAdmin("dir-an");
        UserView binh = seedAdmin("dir-binh");
        UserView dung = seed(binh, "Dung", "MEMBER");
        UserView mine = seed(an, "Mine", "MEMBER");

        Page page = list(an, "?size=100");

        assertThat(ids(page)).containsExactlyInAnyOrder(an.id(), mine.id());
        assertThat(ids(page)).doesNotContain(dung.id(), binh.id());
        assertThat(page.total()).isEqualTo(2);
    }

    @Test
    void staffSeeTheirAdminsScope() {
        UserView admin = seedAdmin("dir-staff-scope");
        UserView member = seed(admin, "Scoped member", "MEMBER");
        UserView manager = seed(admin, "Manager", "OPERATION_MANAGER");

        ResponseEntity<Page> response = get("/api/v1/users?size=100", login(manager.email(), PASSWORD), Page.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ids(response.getBody())).contains(member.id(), admin.id(), manager.id());
    }

    @Test
    void statsCountEachStateAndTheLastSevenDays() {
        UserView admin = seedAdmin("dir-stats");
        Instant now = Instant.now();
        Timestamp old = at(now.minus(Duration.ofDays(30)));
        UserView active1 = seed(admin, "Active 1", "MEMBER");
        UserView active2 = seed(admin, "Active 2", "MEMBER");
        UserView pending = seed(admin, "Pending", "MEMBER");
        UserView locked = seed(admin, "Locked", "MEMBER");
        UserView suspendedOld = seed(admin, "Suspended old", "MEMBER");
        UserView suspendedNew = seed(admin, "Suspended new", "MEMBER");
        for (UserView aged : List.of(active1, active2, pending, locked, suspendedOld)) {
            set(aged.id(), "created_at", old);
        }
        set(pending.id(), "email_confirmed_at", null);
        set(locked.id(), "locked_until", at(now.plus(Duration.ofMinutes(10))));
        set(suspendedOld.id(), "status", "SUSPENDED");
        set(suspendedNew.id(), "status", "SUSPENDED");

        ResponseEntity<Stats> response = get("/api/v1/users/stats", login(admin.email(), PASSWORD), Stats.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(new Stats(7, 3, 1, 1, 2, 2));
        assertThat(list(admin, "?state=active").total()).isEqualTo(3);
    }

    @Test
    void theStatsPathIsNotReadAsAnId() {
        UserView admin = seedAdmin("dir-stats-path");

        ResponseEntity<String> response = get("/api/v1/users/stats", login(admin.email(), PASSWORD), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"total\"");
    }

    @Test
    void aCallerWithoutTheViewKeyIsForbiddenOnTheStats() {
        UserView admin = seedAdmin("dir-stats-forbidden");
        UserView editor = seed(admin, "Editor", "CONTENT_EDITOR");

        ResponseEntity<String> response = get("/api/v1/users/stats", login(editor.email(), PASSWORD), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void anUnknownRoleFilterIsBadRequest() {
        UserView admin = seedAdmin("dir-unknown-role");

        ResponseEntity<String> response = get("/api/v1/users?role=NOPE", login(admin.email(), PASSWORD), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("\"code\":\"role.unknown\"").contains("\"role\"");
    }

    @Test
    void malformedParametersAreBadRequestNamingTheParameter() {
        UserView admin = seedAdmin("dir-validation");
        String token = login(admin.email(), PASSWORD);

        for (String[] bad : new String[][] {
                {"size=101", "size"}, {"size=0", "size"}, {"page=-1", "page"}, {"state=deleted", "state"},
                {"sort=random", "sort"}, {"role=lower", "role"}, {"q=" + "x".repeat(101), "q"}}) {
            ResponseEntity<String> response = get("/api/v1/users?" + bad[0], token, String.class);
            assertThat(response.getStatusCode()).as(bad[0]).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).as(bad[0]).contains("\"" + bad[1] + "\"");
        }
    }

    @Test
    void detailCarriesSignInMethodsAndTheLockout() {
        UserView admin = seedAdmin("dir-detail");
        String token = login(admin.email(), PASSWORD);
        UserView googleOnly = seed(admin, "Google only", "MEMBER");
        set(googleOnly.id(), "has_password", false);
        set(googleOnly.id(), "google_subject", "sub-" + UUID.randomUUID());
        UserView both = seed(admin, "Both", "MEMBER");
        set(both.id(), "google_subject", "sub-" + UUID.randomUUID());
        UserView locked = seed(admin, "Locked", "MEMBER");
        Instant lockoutEnd = Instant.now().plus(Duration.ofMinutes(10));
        set(locked.id(), "locked_until", at(lockoutEnd));

        Row google = get("/api/v1/users/" + googleOnly.id(), token, Row.class).getBody();
        assertThat(google.signInMethods()).containsExactly("GOOGLE");
        assertThat(google.emailConfirmedAt()).isNotNull();
        assertThat(google.state()).isEqualTo("active");
        assertThat(get("/api/v1/users/" + both.id(), token, Row.class).getBody().signInMethods())
                .containsExactly("PASSWORD", "GOOGLE");
        Row lockedRow = get("/api/v1/users/" + locked.id(), token, Row.class).getBody();
        assertThat(lockedRow.state()).isEqualTo("locked");
        assertThat(lockedRow.lockedUntil()).isCloseTo(lockoutEnd, within(1, ChronoUnit.SECONDS));
    }

    @Test
    void staffTargetComesFromTheRoleRow() {
        UserView admin = seedAdmin("dir-staff-target");
        String token = login(admin.email(), PASSWORD);
        UserView manager = seed(admin, "Manager", "OPERATION_MANAGER");
        UserView member = seed(admin, "Plain member", "MEMBER");
        String customRole = "CUSTOM_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        UUID customId = UUID.randomUUID();
        jdbc.update("INSERT INTO role (code, staff, super_admin, system, sort_order) VALUES (?, true, false, false, 99)",
                customRole);
        try {
            jdbc.update("""
                    INSERT INTO app_user (id, owner_id, email, password_hash, name, role, status, created_at, updated_at)
                    VALUES (?, ?, ?, '', 'Custom staff', ?, 'ACTIVE', now(), now())""",
                    customId, admin.id(), uniqueEmail("custom"), customRole);

            Page all = list(admin, "?size=100");
            Map<UUID, Row> byId = all.items().stream().collect(Collectors.toMap(Row::id, row -> row));
            assertThat(byId.get(admin.id()).staffTarget()).isTrue();
            assertThat(byId.get(manager.id()).staffTarget()).isTrue();
            assertThat(byId.get(member.id()).staffTarget()).isFalse();
            assertThat(byId.get(customId).staffTarget()).isTrue();
            assertThat(ids(list(admin, "?role=" + customRole))).containsExactly(customId);
            assertThat(get("/api/v1/users/" + manager.id(), token, Row.class).getBody().staffTarget()).isTrue();
            assertThat(get("/api/v1/users/" + member.id(), token, Row.class).getBody().staffTarget()).isFalse();
        } finally {
            jdbc.update("DELETE FROM app_user WHERE id = ?", customId);
            jdbc.update("DELETE FROM role WHERE code = ?", customRole);
        }
    }
}
