package com.flowcortex.user.phone;

import com.flowcortex.user.phone.CredentialSealer.CredentialUnreadableException;
import com.flowcortex.user.phone.VietguysHttp.Answer;
import com.flowcortex.user.phone.VietguysHttp.NotSent;
import com.flowcortex.user.phone.VietguysHttp.Outcome;
import com.flowcortex.user.phone.VietguysHttp.Unconfirmed;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The VietGuys token chain in {@code sms_provider_credential}, shared by every backend instance. An
 * access token is reused until {@link VietguysTokenPolicy#RENEW_WITHIN} before it lapses; a renewal
 * runs under the row lock so one request reaches VietGuys however many instances ask, and a refresh
 * token the carrier revoked stops every send until the operator reseeds. Logs name the account and
 * the outcome only.
 */
final class VietguysTokenStore implements SmartInitializingSingleton {

    static final String ACCOUNT = "vietguys_sms";
    static final String UNSEEDED = "unseeded";
    static final String ACTIVE = "active";
    static final String RESEED_REQUIRED = "reseed_required";

    private static final Logger LOG = LoggerFactory.getLogger(VietguysTokenStore.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String COLUMNS = "refresh_token_enc, access_token_enc, access_expires_at, state";

    /** No usable access token: not seeded, revoked, unreadable, or the renewal did not succeed. */
    static final class TokenUnavailableException extends RuntimeException {

        TokenUnavailableException(String reason) {
            super("VietGuys access token unavailable: " + reason);
        }
    }

    private record Row(String refreshEnc, String accessEnc, Instant accessExpiresAt, String state) {
    }

    private record Cached(String token, Instant expiresAt) {
    }

    private record Renewed(String token, Instant expiresAt, String failure) {

        static Renewed of(String token, Instant expiresAt) {
            return new Renewed(token, expiresAt, null);
        }

        static Renewed failed(String reason) {
            return new Renewed(null, null, reason);
        }
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate renewalTransaction;
    private final VietguysHttp http;
    private final CredentialSealer sealer;
    private final URI tokenUrl;
    private final String username;
    private final String seed;
    private final Clock clock;
    private final SmsCarrierTelemetry telemetry;
    private final AtomicReference<Cached> cache = new AtomicReference<>();

    VietguysTokenStore(JdbcTemplate jdbc, PlatformTransactionManager transactions, VietguysHttp http,
            CredentialSealer sealer, URI tokenUrl, String username, String seed, Clock clock,
            SmsCarrierTelemetry telemetry) {
        this.jdbc = jdbc;
        this.renewalTransaction = new TransactionTemplate(transactions);
        this.renewalTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.http = http;
        this.sealer = sealer;
        this.tokenUrl = tokenUrl;
        this.username = username;
        this.seed = seed;
        this.clock = clock;
        this.telemetry = telemetry;
    }

    @Override
    public void afterSingletonsInstantiated() {
        seed();
    }

    /**
     * Stores the env seed only when no credential exists or the seed's fingerprint changed, so a
     * restart with the same seed keeps the rotated chain. Storing clears the access token.
     */
    void seed() {
        Instant now = Instant.now(clock);
        int written = jdbc.update("INSERT INTO sms_provider_credential (account, refresh_token_enc, "
                + "access_token_enc, access_expires_at, state, seed_fingerprint, last_renewed_at, updated_at) "
                + "VALUES (?, ?, NULL, NULL, '" + ACTIVE + "', ?, NULL, ?) "
                + "ON CONFLICT (account) DO UPDATE SET refresh_token_enc = EXCLUDED.refresh_token_enc, "
                + "access_token_enc = NULL, access_expires_at = NULL, state = '" + ACTIVE + "', "
                + "seed_fingerprint = EXCLUDED.seed_fingerprint, last_renewed_at = NULL, "
                + "updated_at = EXCLUDED.updated_at "
                + "WHERE sms_provider_credential.seed_fingerprint <> EXCLUDED.seed_fingerprint",
                ACCOUNT, sealer.seal(ACCOUNT, seed), VietguysTokenPolicy.fingerprint(seed), Timestamp.from(now));
        cache.set(null);
        LOG.info("VietGuys credential seed {} account={}", written > 0 ? "stored" : "unchanged", ACCOUNT);
    }

    /**
     * The access token to send with, renewed first when less than two hours are left.
     *
     * @throws TokenUnavailableException when there is none and the renewal could not produce one
     */
    String accessToken() {
        Cached cached = cache.get();
        Instant now = Instant.now(clock);
        if (cached != null && !VietguysTokenPolicy.renewalDue(cached.expiresAt(), now)) {
            return cached.token();
        }
        Row row = read().orElseThrow(() -> new TokenUnavailableException(UNSEEDED));
        Optional<Cached> stored = usableAccessToken(row, now);
        if (stored.isPresent()) {
            cache.set(stored.get());
            return stored.get().token();
        }
        return renew();
    }

    /**
     * Drops a refused access token: the in-memory entry always, the stored one only while the row still
     * holds that same token, so a newer token another instance stored survives.
     */
    void discard(String rejectedAccessToken) {
        cache.set(null);
        Optional<Row> row = read();
        if (row.isEmpty() || row.get().accessEnc() == null) {
            return;
        }
        try {
            if (rejectedAccessToken.equals(sealer.open(ACCOUNT, row.get().accessEnc()))) {
                jdbc.update("UPDATE sms_provider_credential SET access_token_enc = NULL, access_expires_at = NULL, "
                        + "updated_at = ? WHERE account = ? AND access_token_enc = ?",
                        Timestamp.from(Instant.now(clock)), ACCOUNT, row.get().accessEnc());
            }
        } catch (CredentialUnreadableException unreadable) {
            LOG.warn("VietGuys stored access token unreadable account={}", ACCOUNT);
        }
    }

    /** {@code unseeded}, {@code active} or {@code reseed_required}; a ciphertext that does not open is {@code reseed_required}. */
    String credentialState() {
        Optional<Row> row = read();
        if (row.isEmpty()) {
            return UNSEEDED;
        }
        if (RESEED_REQUIRED.equals(row.get().state())) {
            return RESEED_REQUIRED;
        }
        try {
            sealer.open(ACCOUNT, row.get().refreshEnc());
            if (row.get().accessEnc() != null) {
                sealer.open(ACCOUNT, row.get().accessEnc());
            }
            return ACTIVE;
        } catch (CredentialUnreadableException unreadable) {
            return RESEED_REQUIRED;
        }
    }

    private Optional<Cached> usableAccessToken(Row row, Instant now) {
        if (!ACTIVE.equals(row.state())) {
            throw new TokenUnavailableException(RESEED_REQUIRED);
        }
        if (row.accessEnc() == null || VietguysTokenPolicy.renewalDue(row.accessExpiresAt(), now)) {
            return Optional.empty();
        }
        try {
            return Optional.of(new Cached(sealer.open(ACCOUNT, row.accessEnc()), row.accessExpiresAt()));
        } catch (CredentialUnreadableException unreadable) {
            throw new TokenUnavailableException(RESEED_REQUIRED);
        }
    }

    private String renew() {
        Renewed result = renewalTransaction.execute(status -> renewUnderLock());
        if (result == null || result.failure() != null) {
            throw new TokenUnavailableException(result == null ? "failed" : result.failure());
        }
        cache.set(new Cached(result.token(), result.expiresAt()));
        return result.token();
    }

    private Renewed renewUnderLock() {
        List<Row> rows = jdbc.query("SELECT " + COLUMNS + " FROM sms_provider_credential WHERE account = ? FOR UPDATE",
                (rs, i) -> new Row(rs.getString("refresh_token_enc"), rs.getString("access_token_enc"),
                        instant(rs.getTimestamp("access_expires_at")), rs.getString("state")), ACCOUNT);
        if (rows.isEmpty()) {
            return Renewed.failed(UNSEEDED);
        }
        Row row = rows.get(0);
        Instant now = Instant.now(clock);
        Optional<Cached> renewedByAnother;
        String refreshToken;
        try {
            renewedByAnother = usableAccessToken(row, now);
            refreshToken = sealer.open(ACCOUNT, row.refreshEnc());
        } catch (TokenUnavailableException | CredentialUnreadableException reseedRequired) {
            return Renewed.failed(RESEED_REQUIRED);
        }
        if (renewedByAnother.isPresent()) {
            return Renewed.of(renewedByAnother.get().token(), renewedByAnother.get().expiresAt());
        }
        Outcome outcome = http.post(tokenUrl,
                Map.of("Refresh-Token", refreshToken), "application/json",
                requestBody().getBytes(StandardCharsets.UTF_8));
        return switch (outcome) {
            case NotSent notSent -> failed(notSent.reason());
            case Unconfirmed unconfirmed -> failed(unconfirmed.reason());
            case Answer answer -> settle(answer, now);
        };
    }

    private Renewed settle(Answer answer, Instant now) {
        JsonNode body = parse(answer.body());
        switch (VietguysTokenPolicy.renewalAccepted(body)) {
            case RENEWED -> {
                String access = body.path("data").path("access_token").stringValue();
                String refresh = body.path("data").path("refresh_token").stringValue();
                Instant expiresAt = VietguysTokenPolicy.expiryFrom(body, now);
                jdbc.update("UPDATE sms_provider_credential SET refresh_token_enc = ?, access_token_enc = ?, "
                        + "access_expires_at = ?, state = '" + ACTIVE + "', last_renewed_at = ?, updated_at = ? "
                        + "WHERE account = ?",
                        sealer.seal(ACCOUNT, refresh), sealer.seal(ACCOUNT, access), Timestamp.from(expiresAt),
                        Timestamp.from(now), Timestamp.from(now), ACCOUNT);
                telemetry.renewal("renewed");
                LOG.info("VietGuys token renewal account={} outcome=renewed", ACCOUNT);
                return Renewed.of(access, expiresAt);
            }
            case REVOKED -> {
                jdbc.update("UPDATE sms_provider_credential SET state = '" + RESEED_REQUIRED + "', updated_at = ? "
                        + "WHERE account = ?", Timestamp.from(now), ACCOUNT);
                telemetry.renewal("rejected");
                LOG.error("VietGuys token renewal account={} outcome=rejected, reseed required", ACCOUNT);
                return Renewed.failed(RESEED_REQUIRED);
            }
            default -> {
                return failed("unusable answer");
            }
        }
    }

    private Renewed failed(String reason) {
        telemetry.renewal("failed");
        LOG.warn("VietGuys token renewal account={} outcome=failed reason={}", ACCOUNT, reason);
        return Renewed.failed(reason);
    }

    private String requestBody() {
        return JSON.createObjectNode().put("username", username).put("type", "refresh_token").toString();
    }

    private Optional<Row> read() {
        List<Row> rows = jdbc.query("SELECT " + COLUMNS + " FROM sms_provider_credential WHERE account = ?",
                (rs, i) -> new Row(rs.getString("refresh_token_enc"), rs.getString("access_token_enc"),
                        instant(rs.getTimestamp("access_expires_at")), rs.getString("state")), ACCOUNT);
        return rows.stream().findFirst();
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static JsonNode parse(byte[] body) {
        try {
            return JSON.readTree(body);
        } catch (JacksonException unreadable) {
            return null;
        }
    }
}
