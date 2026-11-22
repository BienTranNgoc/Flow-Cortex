package com.flowcortex.connector.application.google;

import com.flowcortex.connector.api.ConnectorConnection;
import com.flowcortex.connector.api.ConnectorCredentials;
import com.flowcortex.connector.api.ConnectorException;
import com.flowcortex.connector.api.ConnectorException.Reason;
import com.flowcortex.connector.api.ConnectorTokens;
import com.flowcortex.connector.application.ConnectorAccess;
import com.flowcortex.connector.application.ConnectorAccess.Granted;
import com.flowcortex.connector.application.google.GoogleErrorRules.Api;
import com.flowcortex.connector.application.google.GoogleErrorRules.Item;
import com.flowcortex.connector.application.google.GoogleWorkspaceApi.Fault;
import com.flowcortex.connector.application.google.GoogleWorkspaceApi.RefreshResult;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The only place that hands out a Google access token: it runs the connector use checks, renews a token that is
 * about to expire and stores the new one. Two concurrent renewals both succeed and the last write wins, which is
 * harmless because Google keeps the refresh token valid for both.
 */
@Service
public class GoogleTokens {

    static final String PROVIDER = "google";
    private static final String INVALID_GRANT = "invalid_grant";
    private static final Logger LOG = LoggerFactory.getLogger(GoogleTokens.class);

    /** A usable access token and the connection it belongs to; {@code toString} omits the token. */
    public record GoogleAccess(String accessToken, ConnectorConnection connection) {

        @Override
        public String toString() {
            return "GoogleAccess[provider=" + PROVIDER + "]";
        }
    }

    private final ConnectorAccess access;
    private final ConnectorCredentials credentials;
    private final GoogleWorkspaceApi api;
    private final GoogleProperties properties;
    private final Clock clock;

    GoogleTokens(ConnectorAccess access, ConnectorCredentials credentials, GoogleWorkspaceApi api,
            GoogleProperties properties, Clock clock) {
        this.access = access;
        this.credentials = credentials;
        this.api = api;
        this.properties = properties;
        this.clock = clock;
    }

    /** A token for a use operation (calling Google for the member), renewed when it expires within two minutes. */
    public GoogleAccess forUse(UUID memberId) {
        return fresh(memberId, access.use(memberId, PROVIDER, properties.enabled()));
    }

    /** A token for a management operation (read, health), which works with usage switched off. */
    public GoogleAccess forManage(UUID memberId) {
        return fresh(memberId, access.manage(memberId, PROVIDER, properties.enabled(), true));
    }

    /** A token renewed now whatever its expiry; for the health check after Google rejected a seemingly fresh one. */
    public GoogleAccess renewNow(UUID memberId) {
        return renew(memberId, access.manage(memberId, PROVIDER, properties.enabled(), true));
    }

    private GoogleAccess fresh(UUID memberId, Granted granted) {
        if (GoogleOAuthRules.needsRenewal(granted.tokens().expiresAt(), clock.instant())) {
            return renew(memberId, granted);
        }
        return new GoogleAccess(granted.tokens().accessToken(), granted.connection());
    }

    private GoogleAccess renew(UUID memberId, Granted granted) {
        ConnectorTokens current = granted.tokens();
        if (current.refreshToken() == null || current.refreshToken().isBlank()) {
            throw revoked(memberId);
        }
        RefreshResult result = api.refresh(current.refreshToken());
        if (!result.ok()) {
            Fault fault = result.fault();
            if (fault != null && INVALID_GRANT.equals(fault.reason())) {
                throw revoked(memberId);
            }
            throw GoogleErrorRules.classify(Api.OAUTH, Item.OTHER, result.status(),
                    fault == null ? null : fault.reason(), fault == null ? null : fault.domain(),
                    fault == null ? null : fault.message(), result.retryAfter());
        }
        Instant expiresAt = clock.instant().plusSeconds(result.expiresInSeconds());
        if (!credentials.refreshTokens(memberId, PROVIDER, result.accessToken(), result.refreshToken(), expiresAt,
                null)) {
            throw ConnectorException.of(Reason.NOT_CONNECTED);
        }
        return new GoogleAccess(result.accessToken(), granted.connection());
    }

    private ConnectorException revoked(UUID memberId) {
        credentials.markRevoked(memberId, PROVIDER);
        LOG.warn("Google grant of member {} is no longer accepted; connection marked revoked", memberId);
        return GoogleErrorRules.reconnectNotConnected();
    }
}
