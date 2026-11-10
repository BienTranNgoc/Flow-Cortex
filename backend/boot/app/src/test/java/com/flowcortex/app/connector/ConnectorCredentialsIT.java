package com.flowcortex.app.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowcortex.AbstractPostgresBootTest;
import com.flowcortex.connector.api.ConnectorConnection;
import com.flowcortex.connector.api.ConnectorCredentials;
import com.flowcortex.connector.api.ConnectorTokens;
import com.flowcortex.connector.api.CredentialUnreadableException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** The store's invariants are the database's: each case runs against the real table through the port. */
@SpringBootTest
class ConnectorCredentialsIT extends AbstractPostgresBootTest {

    private static final Instant EXPIRY = Instant.parse("2026-12-01T00:00:00Z");

    @Autowired
    private ConnectorCredentials credentials;

    @Autowired
    private JdbcTemplate jdbc;

    private static ConnectorTokens tokens(String access, String refresh, List<String> scopes) {
        return new ConnectorTokens(access, refresh, EXPIRY, scopes);
    }

    private int rows(UUID user, String provider) {
        return jdbc.queryForObject("SELECT count(*) FROM connector_auth WHERE user_id = ? AND provider = ?",
                Integer.class, user, provider);
    }

    private Map<String, Object> row(UUID user, String provider) {
        return jdbc.queryForMap("SELECT * FROM connector_auth WHERE user_id = ? AND provider = ?", user, provider);
    }

    private String snapshot(UUID user, String provider) {
        return jdbc.queryForObject("SELECT to_jsonb(c)::text FROM connector_auth c WHERE user_id = ? AND provider = ?",
                String.class, user, provider);
    }

    @Test
    void firstConnectionIsActiveWithUsageOnAndRoundTripsScopes() {
        UUID member = UUID.randomUUID();
        credentials.connect(member, "google", tokens("acc-1", "ref-1", List.of("drive.readonly", "gmail.send")),
                Map.of("email", "m@example.com"));

        ConnectorConnection view = credentials.find(member, "google").orElseThrow();
        assertThat(view.active()).isTrue();
        assertThat(view.usageEnabled()).isTrue();
        assertThat(view.revokedAt()).isNull();
        assertThat(view.scopes()).containsExactly("drive.readonly", "gmail.send");
        assertThat(view.metadata()).containsEntry("email", "m@example.com");
        assertThat(view.expiresAt()).isEqualTo(EXPIRY);

        ConnectorTokens read = credentials.readTokens(member, "google").orElseThrow();
        assertThat(read.accessToken()).isEqualTo("acc-1");
        assertThat(read.refreshToken()).isEqualTo("ref-1");
        assertThat(read.expiresAt()).isEqualTo(EXPIRY);
        assertThat(read.scopes()).containsExactly("drive.readonly", "gmail.send");
        assertThat(read.toString()).doesNotContain("acc-1").doesNotContain("ref-1");
    }

    @Test
    void connectTwiceLeavesOneRowAndResetsFlags() {
        UUID member = UUID.randomUUID();
        credentials.connect(member, "google", tokens("old", "old-r", List.of("a")), Map.of());
        assertThat(credentials.setUsageEnabled(member, "google", false)).isTrue();
        assertThat(credentials.markRevoked(member, "google")).isTrue();

        credentials.connect(member, "google", tokens("new", null, List.of("b")), Map.of());

        assertThat(rows(member, "google")).isEqualTo(1);
        ConnectorConnection view = credentials.find(member, "google").orElseThrow();
        assertThat(view.active()).isTrue();
        assertThat(view.usageEnabled()).isTrue();
        assertThat(view.revokedAt()).isNull();
        assertThat(view.scopes()).containsExactly("b");
        ConnectorTokens read = credentials.readTokens(member, "google").orElseThrow();
        assertThat(read.accessToken()).isEqualTo("new");
        assertThat(read.refreshToken()).isNull();
    }

    @Test
    void concurrentConnectsLeaveOneRow() throws Exception {
        UUID member = UUID.randomUUID();
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                String access = "acc-" + i;
                futures.add(pool.submit(() -> {
                    start.await();
                    credentials.connect(member, "google", tokens(access, "r", List.of()), Map.of());
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(rows(member, "google")).isEqualTo(1);
        assertThat(credentials.readTokens(member, "google").orElseThrow().accessToken()).startsWith("acc-");
    }

    @Test
    void storedTokensAreCiphertextAndDifferAcrossConnects() {
        UUID member = UUID.randomUUID();
        credentials.connect(member, "telegram", tokens("tok-123456", "ref-654321", List.of()), Map.of());
        Map<String, Object> first = row(member, "telegram");
        credentials.connect(member, "telegram", tokens("tok-123456", "ref-654321", List.of()), Map.of());
        Map<String, Object> second = row(member, "telegram");

        String access = (String) first.get("access_token");
        String refresh = (String) first.get("refresh_token");
        assertThat(access).doesNotContain("tok-123456");
        assertThat(refresh).doesNotContain("ref-654321");
        assertThat(second.get("access_token")).isNotEqualTo(access);
        assertThat(second.get("refresh_token")).isNotEqualTo(refresh);
    }

    @Test
    void anotherMemberSeesNothingAndChangesNothing() {
        UUID owner = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        credentials.connect(owner, "google", tokens("acc", "ref", List.of("s")), Map.of());
        String before = snapshot(owner, "google");

        assertThat(credentials.list(other, true)).isEmpty();
        assertThat(credentials.find(other, "google")).isEmpty();
        assertThat(credentials.readTokens(other, "google")).isEmpty();
        assertThat(credentials.refreshTokens(other, "google", "x", "y", EXPIRY, null)).isFalse();
        assertThat(credentials.markRevoked(other, "google")).isFalse();
        assertThat(credentials.setUsageEnabled(other, "google", false)).isFalse();
        assertThat(credentials.disconnect(other, "google")).isFalse();

        assertThat(snapshot(owner, "google")).isEqualTo(before);
        assertThat(credentials.list(owner, false)).hasSize(1);
    }

    @Test
    void listHidesInactiveUnlessAsked() {
        UUID member = UUID.randomUUID();
        credentials.connect(member, "google", tokens("a", null, List.of()), Map.of());
        credentials.connect(member, "telegram", tokens("b", null, List.of()), Map.of());
        credentials.markRevoked(member, "telegram");

        assertThat(credentials.list(member, false)).extracting(ConnectorConnection::provider)
                .containsExactly("google");
        assertThat(credentials.list(member, true)).extracting(ConnectorConnection::provider)
                .containsExactlyInAnyOrder("google", "telegram");
    }

    @Test
    void revokedConnectionRefusesRefreshAndUsageAndKeepsFirstRevocationTime() {
        UUID member = UUID.randomUUID();
        credentials.connect(member, "google", tokens("acc", "ref", List.of()), Map.of());
        assertThat(credentials.markRevoked(member, "google")).isTrue();
        Object revokedAt = row(member, "google").get("revoked_at");
        assertThat(revokedAt).isNotNull();

        assertThat(credentials.refreshTokens(member, "google", "late", "late-r", EXPIRY, null)).isFalse();
        assertThat(credentials.setUsageEnabled(member, "google", false)).isFalse();
        assertThat(credentials.markRevoked(member, "google")).isFalse();

        Map<String, Object> after = row(member, "google");
        assertThat(after.get("revoked_at")).isEqualTo(revokedAt);
        assertThat(after.get("is_active")).isEqualTo(false);
        assertThat(after.get("usage_enabled")).isEqualTo(true);
        assertThat(credentials.readTokens(member, "google")).isEmpty();
        assertThat(credentials.find(member, "google").orElseThrow().active()).isFalse();
    }

    @Test
    void usageSwitchedOffAndOnChangesNothingElse() {
        UUID member = UUID.randomUUID();
        credentials.connect(member, "google", tokens("acc", "ref", List.of("s")), Map.of());
        Map<String, Object> before = row(member, "google");

        assertThat(credentials.setUsageEnabled(member, "google", false)).isTrue();
        assertThat(credentials.find(member, "google").orElseThrow().usageEnabled()).isFalse();
        assertThat(credentials.setUsageEnabled(member, "google", true)).isTrue();

        Map<String, Object> after = row(member, "google");
        assertThat(after.get("access_token")).isEqualTo(before.get("access_token"));
        assertThat(after.get("expires_at")).isEqualTo(before.get("expires_at"));
        assertThat(after.get("is_active")).isEqualTo(true);
        assertThat(after.get("usage_enabled")).isEqualTo(true);
    }

    @Test
    void refreshKeepsUsageOffAndTheStoredRefreshTokenWhenNoneIsGiven() {
        UUID member = UUID.randomUUID();
        credentials.connect(member, "google", tokens("acc", "ref", List.of("s")), Map.of());
        credentials.setUsageEnabled(member, "google", false);
        Instant later = EXPIRY.plus(1, ChronoUnit.DAYS);

        assertThat(credentials.refreshTokens(member, "google", "acc-2", null, later, null)).isTrue();

        ConnectorTokens read = credentials.readTokens(member, "google").orElseThrow();
        assertThat(read.accessToken()).isEqualTo("acc-2");
        assertThat(read.refreshToken()).isEqualTo("ref");
        assertThat(read.expiresAt()).isEqualTo(later);
        assertThat(read.scopes()).containsExactly("s");
        assertThat(credentials.find(member, "google").orElseThrow().usageEnabled()).isFalse();
    }

    @Test
    void refreshWithARotatedRefreshTokenStoresItAndLeavesUsageOff() {
        UUID member = UUID.randomUUID();
        credentials.connect(member, "google", tokens("acc", "ref", List.of("s")), Map.of());
        credentials.setUsageEnabled(member, "google", false);

        assertThat(credentials.refreshTokens(member, "google", "acc-2", "ref-2", EXPIRY, List.of("s", "t")))
                .isTrue();

        ConnectorTokens read = credentials.readTokens(member, "google").orElseThrow();
        assertThat(read.accessToken()).isEqualTo("acc-2");
        assertThat(read.refreshToken()).isEqualTo("ref-2");
        assertThat(read.scopes()).containsExactly("s", "t");
        ConnectorConnection view = credentials.find(member, "google").orElseThrow();
        assertThat(view.usageEnabled()).isFalse();
        assertThat(view.active()).isTrue();
    }

    @Test
    void corruptedCiphertextIsUnreadableWhileANeverConnectedProviderIsEmpty() {
        UUID member = UUID.randomUUID();
        credentials.connect(member, "google", tokens("acc", "ref", List.of()), Map.of());
        String stored = jdbc.queryForObject(
                "SELECT access_token FROM connector_auth WHERE user_id = ? AND provider = 'google'",
                String.class, member);
        char last = stored.charAt(stored.length() - 1);
        String corrupted = stored.substring(0, stored.length() - 1) + (last == 'A' ? 'B' : 'A');
        jdbc.update("UPDATE connector_auth SET access_token = ? WHERE user_id = ? AND provider = 'google'",
                corrupted, member);

        assertThatThrownBy(() -> credentials.readTokens(member, "google"))
                .isInstanceOf(CredentialUnreadableException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("acc"));
        assertThat(credentials.readTokens(member, "telegram")).isEqualTo(Optional.empty());
    }

    @Test
    void ciphertextCopiedToAnotherMemberOrProviderIsUnreadable() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        credentials.connect(a, "google", tokens("acc-a", null, List.of()), Map.of());
        credentials.connect(b, "google", tokens("acc-b", null, List.of()), Map.of());
        credentials.connect(a, "telegram", tokens("acc-t", null, List.of()), Map.of());
        String stored = jdbc.queryForObject(
                "SELECT access_token FROM connector_auth WHERE user_id = ? AND provider = 'google'", String.class, a);
        jdbc.update("UPDATE connector_auth SET access_token = ? WHERE user_id = ? AND provider = 'google'",
                stored, b);
        jdbc.update("UPDATE connector_auth SET access_token = ? WHERE user_id = ? AND provider = 'telegram'",
                stored, a);

        assertThatThrownBy(() -> credentials.readTokens(b, "google"))
                .isInstanceOf(CredentialUnreadableException.class);
        assertThatThrownBy(() -> credentials.readTokens(a, "telegram"))
                .isInstanceOf(CredentialUnreadableException.class);
    }

    @Test
    void disconnectTwiceReturnsTrueThenFalseAndAllowsAFirstConnection() {
        UUID member = UUID.randomUUID();
        credentials.connect(member, "google", tokens("acc", "ref", List.of()), Map.of());

        assertThat(credentials.disconnect(member, "google")).isTrue();
        assertThat(credentials.disconnect(member, "google")).isFalse();
        assertThat(rows(member, "google")).isZero();

        credentials.connect(member, "google", tokens("acc-2", null, List.of()), Map.of());
        assertThat(credentials.find(member, "google")).isPresent();
    }

    private static Map<String, Object> chat(long id) {
        return Map.of("id", id, "title", "Team", "type", "group");
    }

    private UUID telegramMember() {
        UUID member = UUID.randomUUID();
        credentials.connect(member, "telegram", tokens("bot-token", null, List.of()),
                Map.of("botUsername", "nodi_bot", "chats", List.of(chat(42L)), "defaultChatId", 42));
        return member;
    }

    @Test
    void mergeMetadataKeepsEveryOtherKey() {
        UUID member = telegramMember();

        boolean merged = credentials.mergeMetadata(member, "telegram",
                Map.of("chats", List.of(chat(42L), chat(-1001L))), null);

        assertThat(merged).isTrue();
        Map<String, Object> metadata = credentials.find(member, "telegram").orElseThrow().metadata();
        assertThat(metadata).containsEntry("botUsername", "nodi_bot").containsEntry("defaultChatId", 42);
        assertThat((List<?>) metadata.get("chats")).hasSize(2);
        assertThat(credentials.readTokens(member, "telegram").orElseThrow().accessToken()).isEqualTo("bot-token");
    }

    @Test
    void mergeMetadataLeavesUsageOffAndRevocationAlone() {
        UUID member = telegramMember();
        assertThat(credentials.setUsageEnabled(member, "telegram", false)).isTrue();

        assertThat(credentials.mergeMetadata(member, "telegram", Map.of("defaultChatId", 7), null)).isTrue();

        ConnectorConnection view = credentials.find(member, "telegram").orElseThrow();
        assertThat(view.usageEnabled()).isFalse();
        assertThat(view.active()).isTrue();
        assertThat(view.metadata()).containsEntry("defaultChatId", 7);
    }

    @Test
    void mergeMetadataOnARevokedRowChangesNothing() {
        UUID member = telegramMember();
        assertThat(credentials.markRevoked(member, "telegram")).isTrue();
        String before = snapshot(member, "telegram");

        assertThat(credentials.mergeMetadata(member, "telegram", Map.of("defaultChatId", 7), null)).isFalse();

        assertThat(snapshot(member, "telegram")).isEqualTo(before);
    }

    @Test
    void mergeMetadataForAnotherMembersProviderOrAnAbsentRowIsFalse() {
        UUID owner = telegramMember();
        UUID other = UUID.randomUUID();
        String before = snapshot(owner, "telegram");

        assertThat(credentials.mergeMetadata(other, "telegram", Map.of("defaultChatId", 7), null)).isFalse();
        assertThat(credentials.mergeMetadata(owner, "notion-main", Map.of("defaultChatId", 7), null)).isFalse();

        assertThat(snapshot(owner, "telegram")).isEqualTo(before);
        assertThat(rows(other, "telegram")).isZero();
    }

    @Test
    void mergeMetadataWithARequirementPatchesOnlyWhenTheStoredMetadataContainsIt() {
        UUID member = telegramMember();
        Map<String, Object> requires = Map.of("chats", List.of(Map.of("id", 42L)));

        assertThat(credentials.mergeMetadata(member, "telegram", Map.of("defaultChatId", 42), requires)).isTrue();

        credentials.connect(member, "telegram", tokens("bot-token", null, List.of()),
                Map.of("botUsername", "nodi_bot", "chats", List.of(chat(-1001L))));
        String before = snapshot(member, "telegram");

        assertThat(credentials.mergeMetadata(member, "telegram", Map.of("defaultChatId", 42), requires)).isFalse();

        assertThat(snapshot(member, "telegram")).isEqualTo(before);
        assertThat(credentials.find(member, "telegram").orElseThrow().metadata()).doesNotContainKey("defaultChatId");
    }

    @Test
    void invalidProviderSlugIsRejected() {
        UUID member = UUID.randomUUID();
        assertThatThrownBy(() -> credentials.connect(member, "Google!", tokens("a", null, List.of()), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> credentials.readTokens(member, ""))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
