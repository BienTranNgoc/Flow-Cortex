package com.flowcortex.user.phone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowcortex.AbstractPostgresBootTest;
import com.flowcortex.user.phone.SmsMessage.Kind;
import com.flowcortex.user.phone.VietguysTokenStore.TokenUnavailableException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The VietGuys token chain against the real database and a stub carrier on a local port. The stub
 * answers on plain http and no proxy is involved; the proxy path is locked by {@code VietguysHttpTest}.
 * Lives in the production package because the store and its collaborators are package-private.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class VietguysTokenStoreIT extends AbstractPostgresBootTest {

    private static final String SEED = "seed-refresh-token-0001";
    private static final String KEY_1 = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String KEY_2 = Base64.getEncoder()
            .encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
    private static final Clock CLOCK = Clock.systemUTC();

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactions;

    private HttpServer carrier;
    private final AtomicInteger tokenRequests = new AtomicInteger();
    private final AtomicInteger smsRequests = new AtomicInteger();
    private final AtomicReference<String> tokenAnswer = new AtomicReference<>();
    private final AtomicReference<Long> tokenDelayMillis = new AtomicReference<>(0L);
    private final AtomicReference<CountDownLatch> tokenHold = new AtomicReference<>(new CountDownLatch(0));
    private final ConcurrentLinkedQueue<String> smsAnswers = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<String> smsRequestBodies = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<String> smsAccessTokens = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<String> refreshHeaders = new ConcurrentLinkedQueue<>();
    private ExecutorService stubThreads;

    @BeforeEach
    void startCarrier() throws IOException {
        jdbc.update("DELETE FROM sms_provider_credential");
        stubThreads = Executors.newCachedThreadPool();
        carrier = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        carrier.setExecutor(stubThreads);
        carrier.createContext("/token", exchange -> {
            tokenRequests.incrementAndGet();
            refreshHeaders.add(String.valueOf(exchange.getRequestHeaders().getFirst("Refresh-Token")));
            exchange.getRequestBody().readAllBytes();
            await(tokenHold.get());
            sleep(tokenDelayMillis.get());
            reply(exchange, 200, tokenAnswer.get());
        });
        carrier.createContext("/sms", exchange -> {
            smsRequests.incrementAndGet();
            smsAccessTokens.add(String.valueOf(exchange.getRequestHeaders().getFirst("Access-Token")));
            smsRequestBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String answer = smsAnswers.poll();
            reply(exchange, answer != null && answer.contains("-5") ? 401 : 200, answer == null ? "{\"error\":0}" : answer);
        });
        carrier.start();
        tokenAnswer.set(renewalAnswer("access-2", "refresh-2", Instant.now().plus(Duration.ofHours(24))));
    }

    @AfterEach
    void stopCarrier() {
        carrier.stop(0);
        stubThreads.shutdownNow();
        jdbc.update("DELETE FROM sms_provider_credential");
    }

    @Test
    void twoConcurrentReadersWithADueTokenCauseOneRenewal() throws Exception {
        VietguysTokenStore first = store(ring("k1", KEY_1), "k1", SEED, Duration.ofSeconds(5));
        VietguysTokenStore second = store(ring("k1", KEY_1), "k1", SEED, Duration.ofSeconds(5));
        first.seed();
        storeAccessToken(sealer(ring("k1", KEY_1), "k1"), "access-1", Instant.now().plus(Duration.ofHours(1)));
        tokenDelayMillis.set(300L);

        CountDownLatch go = new CountDownLatch(1);
        ExecutorService readers = Executors.newFixedThreadPool(2);
        try {
            Future<String> a = readers.submit(() -> {
                go.await();
                return first.accessToken();
            });
            Future<String> b = readers.submit(() -> {
                go.await();
                return second.accessToken();
            });
            go.countDown();
            assertThat(a.get(10, TimeUnit.SECONDS)).isEqualTo("access-2");
            assertThat(b.get(10, TimeUnit.SECONDS)).isEqualTo("access-2");
        } finally {
            readers.shutdownNow();
        }

        assertThat(tokenRequests.get()).isEqualTo(1);
        assertThat(refreshHeaders).containsExactly(SEED);
    }

    @Test
    void aRevokedRefreshTokenMarksReseedRequired() {
        VietguysTokenStore store = store(ring("k1", KEY_1), "k1", SEED, Duration.ofSeconds(5));
        store.seed();
        tokenAnswer.set("{\"error\":-15}");

        assertThatThrownBy(store::accessToken).isInstanceOf(TokenUnavailableException.class);

        assertThat(state()).isEqualTo("reseed_required");
        assertThat(store.credentialState()).isEqualTo("reseed_required");
        assertThatThrownBy(store::accessToken).isInstanceOf(TokenUnavailableException.class);
        assertThat(tokenRequests.get()).isEqualTo(1);
    }

    @Test
    void aRenewalTimeoutKeepsTheChain() {
        VietguysTokenStore store = store(ring("k1", KEY_1), "k1", SEED, Duration.ofSeconds(1));
        store.seed();
        String sealedBefore = jdbc.queryForObject("SELECT refresh_token_enc FROM sms_provider_credential", String.class);
        // A fixed stub delay races the read timeout: a client stall longer than the gap lets the answer in.
        CountDownLatch held = new CountDownLatch(1);
        tokenHold.set(held);
        try {
            assertThatThrownBy(store::accessToken).isInstanceOf(TokenUnavailableException.class);
        } finally {
            tokenHold.set(new CountDownLatch(0));
            held.countDown();
        }

        assertThat(jdbc.queryForObject("SELECT refresh_token_enc FROM sms_provider_credential", String.class))
                .isEqualTo(sealedBefore);
        assertThat(state()).isEqualTo("active");
        assertThat(store.accessToken()).isEqualTo("access-2");
        assertThat(tokenRequests.get()).isEqualTo(2);
    }

    @Test
    void aRenewalWithoutAnExpiryIsReusedAndCountedAsFallback() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        VietguysTokenStore store = new VietguysTokenStore(jdbc, transactions,
                new VietguysHttp(Duration.ofSeconds(5), Duration.ofSeconds(5)), sealer(ring("k1", KEY_1), "k1"),
                carrierUrl("/token"), "demo-user", SEED, CLOCK, new SmsCarrierTelemetry(meters));
        store.seed();
        tokenAnswer.set("{\"error\":0,\"data\":{\"access_token\":\"access-2\",\"refresh_token\":\"refresh-2\"}}");

        assertThat(store.accessToken()).isEqualTo("access-2");
        assertThat(store.accessToken()).isEqualTo("access-2");
        assertThat(store(ring("k1", KEY_1), "k1", SEED, Duration.ofSeconds(5)).accessToken()).isEqualTo("access-2");

        assertThat(tokenRequests.get()).isEqualTo(1);
        assertThat(meters.get(SmsCarrierTelemetry.RENEWAL).tag("outcome", "renewed_fallback").counter().count())
                .isEqualTo(1.0);
        assertThat(meters.find(SmsCarrierTelemetry.RENEWAL).tag("outcome", "renewed").counter()).isNull();
    }

    @Test
    void theSameSeedAfterARestartKeepsTheRotatedChain() {
        CredentialSealer sealer = sealer(ring("k1", KEY_1), "k1");
        store(ring("k1", KEY_1), "k1", SEED, Duration.ofSeconds(5)).seed();
        store(ring("k1", KEY_1), "k1", SEED, Duration.ofSeconds(5)).accessToken();

        VietguysTokenStore restarted = store(ring("k1", KEY_1), "k1", SEED, Duration.ofSeconds(5));
        restarted.seed();

        assertThat(sealer.open(VietguysTokenStore.ACCOUNT, column("refresh_token_enc"))).isEqualTo("refresh-2");
        assertThat(restarted.accessToken()).isEqualTo("access-2");
        assertThat(tokenRequests.get()).isEqualTo(1);
    }

    @Test
    void aNewSeedReplacesTheChain() {
        CredentialSealer sealer = sealer(ring("k1", KEY_1), "k1");
        store(ring("k1", KEY_1), "k1", SEED, Duration.ofSeconds(5)).seed();
        tokenAnswer.set("{\"error\":-15}");
        assertThatThrownBy(() -> store(ring("k1", KEY_1), "k1", SEED, Duration.ofSeconds(5)).accessToken())
                .isInstanceOf(TokenUnavailableException.class);
        assertThat(state()).isEqualTo("reseed_required");

        VietguysTokenStore reseeded = store(ring("k1", KEY_1), "k1", "seed-refresh-token-0002", Duration.ofSeconds(5));
        reseeded.seed();

        assertThat(state()).isEqualTo("active");
        assertThat(sealer.open(VietguysTokenStore.ACCOUNT, column("refresh_token_enc"))).isEqualTo("seed-refresh-token-0002");
        assertThat(column("access_token_enc")).isNull();
        tokenAnswer.set(renewalAnswer("access-3", "refresh-3", Instant.now().plus(Duration.ofHours(24))));
        assertThat(reseeded.accessToken()).isEqualTo("access-3");
        assertThat(refreshHeaders).last().isEqualTo("seed-refresh-token-0002");
    }

    @Test
    void tokensAreStoredOnlyAsCiphertext() {
        VietguysTokenStore store = store(ring("k1", KEY_1), "k1", SEED, Duration.ofSeconds(5));
        store.seed();
        store.accessToken();

        String raw = jdbc.queryForObject(
                "SELECT row_to_json(c)::text FROM sms_provider_credential c", String.class);

        assertThat(raw).doesNotContain("access-2", "refresh-2", SEED);
        assertThat(column("refresh_token_enc")).startsWith("k1.");
        assertThat(column("access_token_enc")).startsWith("k1.");
    }

    @Test
    void aWrongKeyReadsReseedRequired() {
        store(ring("k1", KEY_1), "k1", SEED, Duration.ofSeconds(5)).seed();
        VietguysTokenStore otherRing = store(ring("k9", KEY_2), "k9", SEED, Duration.ofSeconds(5));

        otherRing.seed();

        assertThat(otherRing.credentialState()).isEqualTo("reseed_required");
        assertThatThrownBy(otherRing::accessToken).isInstanceOf(TokenUnavailableException.class);
        assertThat(tokenRequests.get()).isZero();
    }

    @Test
    void aRotatedRingStillReadsAndTheNextRenewalReseals() {
        Map<String, String> before = ring("k1", KEY_1);
        store(before, "k1", SEED, Duration.ofSeconds(5)).seed();
        storeAccessToken(sealer(before, "k1"), "access-1", Instant.now().plus(Duration.ofHours(5)));
        Map<String, String> rotated = Map.of("k1", KEY_1, "k2", KEY_2);
        VietguysTokenStore store = store(rotated, "k2", SEED, Duration.ofSeconds(5));

        assertThat(store.accessToken()).isEqualTo("access-1");
        assertThat(tokenRequests.get()).isZero();

        jdbc.update("UPDATE sms_provider_credential SET access_expires_at = ?",
                Timestamp.from(Instant.now().plus(Duration.ofHours(1))));
        VietguysTokenStore afterRestart = store(rotated, "k2", SEED, Duration.ofSeconds(5));
        assertThat(afterRestart.accessToken()).isEqualTo("access-2");

        assertThat(column("refresh_token_enc")).startsWith("k2.");
        assertThat(column("access_token_enc")).startsWith("k2.");
        assertThat(tokenRequests.get()).isEqualTo(1);
    }

    @Test
    void aRefusedTokenThenAcceptedSendsOnce() {
        VietguysTokenStore store = store(ring("k1", KEY_1), "k1", SEED, Duration.ofSeconds(5));
        store.seed();
        storeAccessToken(sealer(ring("k1", KEY_1), "k1"), "access-1", Instant.now().plus(Duration.ofHours(5)));
        smsAnswers.add("{\"error\":1,\"error_code\":-5}");
        smsAnswers.add("{\"error\":0}");
        VietguysSmsSender sender = new VietguysSmsSender(
                new VietguysHttp(Duration.ofSeconds(5), Duration.ofSeconds(5)), store,
                SmsTemplates.validated("Ma xac minh cua ban la {CODE}", "Da doi so", "Da hoan tac"),
                carrierUrl("/sms"), "demo-user", "FLOWCORTEX", new SmsCarrierTelemetry(new SimpleMeterRegistry()));

        SmsDelivery delivery = sender.send(new SmsMessage("+84912345678", Kind.OTP, "482913", Locale.ENGLISH, "bid-42"));

        assertThat(delivery).isEqualTo(SmsDelivery.ACCEPTED);
        assertThat(smsRequests.get()).isEqualTo(2);
        assertThat(smsAccessTokens).containsExactly("access-1", "access-2");
        assertThat(List.copyOf(smsRequestBodies)).allSatisfy(body -> assertThat(body).contains("bid-42"));
        assertThat(tokenRequests.get()).isEqualTo(1);
    }

    private VietguysTokenStore store(Map<String, String> keys, String current, String seed, Duration readTimeout) {
        return new VietguysTokenStore(jdbc, transactions, new VietguysHttp(Duration.ofSeconds(5), readTimeout),
                sealer(keys, current), carrierUrl("/token"), "demo-user", seed, CLOCK,
                new SmsCarrierTelemetry(new SimpleMeterRegistry()));
    }

    private static CredentialSealer sealer(Map<String, String> keys, String current) {
        return new CredentialSealer(keys, current);
    }

    private static Map<String, String> ring(String id, String key) {
        return Map.of(id, key);
    }

    private void storeAccessToken(CredentialSealer sealer, String token, Instant expiresAt) {
        jdbc.update("UPDATE sms_provider_credential SET access_token_enc = ?, access_expires_at = ?",
                sealer.seal(VietguysTokenStore.ACCOUNT, token), Timestamp.from(expiresAt));
    }

    private String state() {
        return jdbc.queryForObject("SELECT state FROM sms_provider_credential", String.class);
    }

    private String column(String name) {
        return jdbc.queryForObject("SELECT " + name + " FROM sms_provider_credential", String.class);
    }

    private URI carrierUrl(String path) {
        return URI.create("http://127.0.0.1:" + carrier.getAddress().getPort() + path);
    }

    private static String renewalAnswer(String access, String refresh, Instant expiresAt) {
        return "{\"error\":0,\"data\":{\"access_token\":\"" + access + "\",\"refresh_token\":\"" + refresh
                + "\",\"expired_at\":" + expiresAt.toEpochMilli() + "}}";
    }

    private static void reply(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
