package com.flowcortex.connector.application;

import com.flowcortex.connector.api.ConnectorConnection;
import com.flowcortex.connector.api.ConnectorCredentials;
import com.flowcortex.connector.api.ConnectorTokens;
import com.flowcortex.connector.domain.ConnectorAuth;
import com.flowcortex.connector.infrastructure.ConnectorAuthRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Encrypts on the way in, decrypts only in {@link #readTokens} and in the handle {@link #readActive} returns;
 * logs the provider and the outcome, never a token.
 */
@Service
class ConnectorCredentialsImpl implements ConnectorCredentials {

    private static final Logger LOG = LoggerFactory.getLogger(ConnectorCredentialsImpl.class);
    private static final TypeReference<Map<String, Object>> METADATA = new TypeReference<>() { };

    private final ConnectorAuthRepository repository;
    private final CredentialCipher cipher;
    private final Clock clock;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    ConnectorCredentialsImpl(ConnectorAuthRepository repository, CredentialCipher cipher, Clock clock) {
        this.repository = repository;
        this.cipher = cipher;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void connect(UUID userId, String provider, ConnectorTokens tokens, Map<String, Object> metadata) {
        requireProvider(provider);
        repository.upsert(UUID.randomUUID(), userId, provider,
                cipher.encrypt(userId, provider, tokens.accessToken()),
                encryptOrNull(userId, provider, tokens.refreshToken()),
                tokens.expiresAt(), scopesArray(tokens.scopes(), true), serialize(metadata), Instant.now(clock));
        LOG.info("Connector {} connected", provider);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ConnectorConnection> list(UUID userId, boolean includeInactive) {
        List<ConnectorAuth> rows = includeInactive
                ? repository.findByUserIdOrderByProvider(userId)
                : repository.findByUserIdAndActiveTrueOrderByProvider(userId);
        return rows.stream().map(this::view).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ConnectorConnection> find(UUID userId, String provider) {
        requireProvider(provider);
        return repository.findByUserIdAndProvider(userId, provider).map(this::view);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ConnectorTokens> readTokens(UUID userId, String provider) {
        requireProvider(provider);
        return repository.findByUserIdAndProvider(userId, provider)
                .filter(ConnectorAuth::isActive)
                .map(row -> decrypt(userId, provider, row));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ActiveCredential> readActive(UUID userId, String provider) {
        requireProvider(provider);
        return repository.findByUserIdAndProvider(userId, provider)
                .filter(ConnectorAuth::isActive)
                .map(row -> new ActiveCredential(view(row), () -> decrypt(userId, provider, row)));
    }

    private ConnectorTokens decrypt(UUID userId, String provider, ConnectorAuth row) {
        try {
            String refresh = row.getRefreshToken();
            return new ConnectorTokens(cipher.decrypt(userId, provider, row.getAccessToken()),
                    refresh == null ? null : cipher.decrypt(userId, provider, refresh),
                    row.getExpiresAt(), List.copyOf(row.getScopes()));
        } catch (RuntimeException ex) {
            LOG.warn("Connector {} credential unreadable", provider);
            throw ex;
        }
    }

    @Override
    @Transactional
    public boolean refreshTokens(UUID userId, String provider, String accessToken, String refreshToken,
            Instant expiresAt, List<String> scopes) {
        requireProvider(provider);
        int changed = repository.refresh(userId, provider, cipher.encrypt(userId, provider, accessToken),
                encryptOrNull(userId, provider, refreshToken), expiresAt, scopesArray(scopes, false),
                Instant.now(clock));
        LOG.info("Connector {} refresh {}", provider, changed > 0 ? "stored" : "ignored");
        return changed > 0;
    }

    @Override
    @Transactional
    public boolean markRevoked(UUID userId, String provider) {
        requireProvider(provider);
        boolean changed = repository.revoke(userId, provider, Instant.now(clock)) > 0;
        LOG.info("Connector {} revoke {}", provider, changed ? "applied" : "ignored");
        return changed;
    }

    @Override
    @Transactional
    public boolean setUsageEnabled(UUID userId, String provider, boolean enabled) {
        requireProvider(provider);
        boolean changed = repository.setUsage(userId, provider, enabled, Instant.now(clock)) > 0;
        LOG.info("Connector {} usage switch {}", provider, changed ? "applied" : "ignored");
        return changed;
    }

    @Override
    @Transactional
    public boolean mergeMetadata(UUID userId, String provider, Map<String, Object> patch,
            Map<String, Object> requires) {
        requireProvider(provider);
        if (patch == null) {
            throw new IllegalArgumentException("patch must not be null");
        }
        boolean changed = repository.mergeMetadata(userId, provider, serialize(patch),
                requires == null ? null : serialize(requires), Instant.now(clock)) > 0;
        LOG.info("Connector {} metadata merge {}", provider, changed ? "applied" : "ignored");
        return changed;
    }

    @Override
    @Transactional
    public boolean disconnect(UUID userId, String provider) {
        requireProvider(provider);
        boolean deleted = repository.deleteByUserAndProvider(userId, provider) > 0;
        LOG.info("Connector {} disconnect {}", provider, deleted ? "deleted" : "found nothing");
        return deleted;
    }

    private ConnectorConnection view(ConnectorAuth row) {
        return new ConnectorConnection(row.getProvider(), List.copyOf(row.getScopes()), parse(row.getMetadata()),
                row.getExpiresAt(), row.isActive(), row.isUsageEnabled(), row.getRevokedAt(), row.getCreatedAt(),
                row.getUpdatedAt());
    }

    private String encryptOrNull(UUID userId, String provider, String token) {
        return token == null ? null : cipher.encrypt(userId, provider, token);
    }

    private static String[] scopesArray(List<String> scopes, boolean emptyWhenNull) {
        if (scopes == null) {
            return emptyWhenNull ? new String[0] : null;
        }
        return scopes.toArray(String[]::new);
    }

    private static void requireProvider(String provider) {
        if (provider == null || !ConnectorCredentials.PROVIDER_PATTERN.matcher(provider).matches()) {
            throw new IllegalArgumentException(
                    "provider must match " + ConnectorCredentials.PROVIDER_PATTERN.pattern());
        }
    }

    private String serialize(Map<String, Object> metadata) {
        return metadata == null || metadata.isEmpty() ? "{}" : jsonMapper.writeValueAsString(metadata);
    }

    private Map<String, Object> parse(String metadata) {
        return metadata == null || metadata.isBlank() ? Map.of() : jsonMapper.readValue(metadata, METADATA);
    }
}
