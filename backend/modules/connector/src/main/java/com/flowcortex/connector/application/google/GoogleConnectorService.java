package com.flowcortex.connector.application.google;

import com.flowcortex.connector.api.ConnectorConnection;
import com.flowcortex.connector.api.ConnectorCredentials;
import com.flowcortex.connector.api.ConnectorException;
import com.flowcortex.connector.api.ConnectorException.Reason;
import com.flowcortex.connector.api.ConnectorTokens;
import com.flowcortex.connector.application.ConnectorAccess;
import com.flowcortex.connector.application.ConnectorFlowSealer;
import com.flowcortex.connector.application.ConnectorFlowSealer.Flow;
import com.flowcortex.connector.application.google.GoogleErrorRules.Api;
import com.flowcortex.connector.application.google.GoogleErrorRules.Item;
import com.flowcortex.connector.application.google.GoogleOAuthRules.GrantFault;
import com.flowcortex.connector.application.google.GoogleTokens.GoogleAccess;
import com.flowcortex.connector.application.google.GoogleWorkspaceApi.Reply;
import com.flowcortex.connector.application.google.GoogleWorkspaceApi.TokenGrant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Connecting, reading, checking and disconnecting a member's Google account. The member of a callback comes only
 * from the sealed flow, never from the request; no code, token, verifier or secret reaches a log line, a result or
 * a failure from here.
 */
@Service
public class GoogleConnectorService {

    static final String PROVIDER = "google";
    static final String CONNECTED = "connected";
    static final String CANCELLED = "cancelled";
    static final String INVALID_STATE = "invalid_state";
    static final String SCOPE_MISSING = "scope_missing";
    static final String TURNED_OFF = "turned_off";
    static final String FAILED = "failed";
    static final String TOKEN_REJECTED = "tokenRejected";

    private static final String ACCESS_DENIED = "access_denied";
    private static final Logger LOG = LoggerFactory.getLogger(GoogleConnectorService.class);

    /** The consent URL and the sealed flow the browser carries back to the callback. */
    public record AuthorizeStart(String url, String sealedFlow) {

        @Override
        public String toString() {
            return "AuthorizeStart[]";
        }
    }

    /** The stored connection as the API shows it: never a token. */
    public record GoogleConnection(String provider, String email, List<String> scopes, List<String> missingScopes,
            Instant expiresAt, boolean usageEnabled) {
    }

    /** Whether Google still accepts the stored grant; {@code reason} is {@code tokenRejected} when it does not. */
    public record GoogleHealth(boolean valid, String email, String reason) {
    }

    private final ConnectorAccess access;
    private final ConnectorCredentials credentials;
    private final GoogleWorkspaceApi api;
    private final GoogleTokens tokens;
    private final ConnectorFlowSealer sealer;
    private final GoogleProperties properties;
    private final Clock clock;

    GoogleConnectorService(ConnectorAccess access, ConnectorCredentials credentials, GoogleWorkspaceApi api,
            GoogleTokens tokens, ConnectorFlowSealer sealer, GoogleProperties properties, Clock clock) {
        this.access = access;
        this.credentials = credentials;
        this.api = api;
        this.tokens = tokens;
        this.sealer = sealer;
        this.properties = properties;
        this.clock = clock;
    }

    /** Starts a connect: the member and the switch are checked, then a fresh flow is sealed and its consent URL built. */
    public AuthorizeStart authorize(UUID memberId) {
        access.requireMember(memberId, properties.enabled());
        Flow flow = Flow.begin(memberId, GoogleOAuthRules.FLOW_TTL, clock);
        String url = GoogleOAuthRules.authorizationUrl(properties, flow.state(), flow.codeChallenge());
        return new AuthorizeStart(url, sealer.seal(flow));
    }

    /**
     * Completes a connect and returns the {@code result} the browser is sent back with. It never throws: every
     * failure is one of the result codes, and nothing is stored unless the result is {@code connected}.
     */
    public String callback(String sealedFlow, String state, String code, String error) {
        Optional<Flow> opened = sealer.open(sealedFlow, clock.instant());
        if (opened.isEmpty() || !sameState(opened.get().state(), state)) {
            return finish(null, INVALID_STATE);
        }
        Flow flow = opened.get();
        String result;
        try {
            result = complete(flow, code, error);
        } catch (RuntimeException ex) {
            LOG.warn("Google connect for member {} failed: {}", flow.memberId(), describe(ex));
            result = FAILED;
        }
        return finish(flow.memberId(), result);
    }

    /** The stored connection: no Google call, no token read, works with usage off. */
    public GoogleConnection read(UUID memberId) {
        ConnectorConnection connection = access.manage(memberId, PROVIDER, properties.enabled(), false)
                .connection();
        List<String> scopes = connection.scopes() == null ? List.of() : connection.scopes();
        return new GoogleConnection(PROVIDER, emailOf(connection), scopes, GoogleOAuthRules.missingScopes(scopes),
                connection.expiresAt(), connection.usageEnabled());
    }

    /**
     * Asks Google whether the stored grant still works. A grant Google no longer accepts, including one whose
     * renewal Google refuses during the check, is an answer ({@code valid} false), not a failure.
     */
    public GoogleHealth health(UUID memberId) {
        GoogleAccess granted;
        try {
            granted = tokens.forManage(memberId);
        } catch (ConnectorException ex) {
            return rejectedOrRethrow(ex);
        }
        Reply reply = api.userinfo(granted.accessToken());
        if (reply.status() == 401) {
            try {
                granted = tokens.renewNow(memberId);
            } catch (ConnectorException ex) {
                return rejectedOrRethrow(ex);
            }
            reply = api.userinfo(granted.accessToken());
            if (reply.status() == 401) {
                return rejected();
            }
        }
        if (!reply.ok()) {
            throw failure(reply);
        }
        String email = emailOf(reply.body());
        return new GoogleHealth(true, email != null ? email : emailOf(granted.connection()), null);
    }

    /**
     * Asks Google to revoke the stored grant before the connection row is deleted. Whatever goes wrong here
     * (switch off, member suspended, nothing readable, Google unreachable) is swallowed so the delete proceeds.
     */
    public void revokeBeforeDisconnect(UUID memberId) {
        if (!properties.enabled()) {
            return;
        }
        try {
            ConnectorTokens stored = access.manage(memberId, PROVIDER, true, true).tokens();
            String token = stored.refreshToken() != null && !stored.refreshToken().isBlank()
                    ? stored.refreshToken() : stored.accessToken();
            api.revoke(token);
        } catch (RuntimeException ex) {
            LOG.info("Google revoke for member {} skipped: {}", memberId, describe(ex));
        }
    }

    private String complete(Flow flow, String code, String error) {
        if (!properties.enabled()) {
            return TURNED_OFF;
        }
        if (error != null && !error.isBlank()) {
            return ACCESS_DENIED.equals(error) ? CANCELLED : FAILED;
        }
        if (code == null || code.isBlank()) {
            return FAILED;
        }
        access.requireMember(flow.memberId(), true);
        TokenGrant grant = api.exchangeCode(code, flow.verifier());
        if (!grant.ok()) {
            LOG.warn("Google refused the code exchange of member {} with status {}", flow.memberId(), grant.status());
            return FAILED;
        }
        GrantFault fault = GoogleOAuthRules.grantFault(grant.scopes(), grant.refreshToken());
        if (fault == GrantFault.SCOPE_MISSING) {
            return SCOPE_MISSING;
        }
        if (fault != null) {
            return FAILED;
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("email", fetchEmail(grant.accessToken()));
        Instant expiresAt = clock.instant().plusSeconds(grant.expiresInSeconds());
        credentials.connect(flow.memberId(), PROVIDER,
                new ConnectorTokens(grant.accessToken(), grant.refreshToken(), expiresAt, grant.scopes()), metadata);
        return CONNECTED;
    }

    private String fetchEmail(String accessToken) {
        try {
            Reply reply = api.userinfo(accessToken);
            return reply.ok() ? emailOf(reply.body()) : null;
        } catch (RuntimeException ex) {
            LOG.info("Google userinfo unavailable after connect: {}", describe(ex));
            return null;
        }
    }

    private GoogleHealth rejectedOrRethrow(ConnectorException ex) {
        if (ex.reason() == Reason.NOT_CONNECTED && GoogleErrorRules.RECONNECT.equals(ex.providerReason())) {
            return rejected();
        }
        throw ex;
    }

    private static GoogleHealth rejected() {
        return new GoogleHealth(false, null, TOKEN_REJECTED);
    }

    private static ConnectorException failure(Reply reply) {
        GoogleWorkspaceApi.Fault fault = reply.fault();
        return GoogleErrorRules.classify(Api.OAUTH, Item.OTHER, reply.status(),
                fault == null ? null : fault.reason(), fault == null ? null : fault.domain(),
                fault == null ? null : fault.message(), reply.retryAfter());
    }

    private static boolean sameState(String expected, String actual) {
        return actual != null && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    private static String emailOf(Map<String, Object> source) {
        return source != null && source.get("email") instanceof String email && !email.isBlank() ? email : null;
    }

    private static String emailOf(ConnectorConnection connection) {
        return emailOf(connection.metadata());
    }

    private static String describe(RuntimeException ex) {
        return ex instanceof ConnectorException connector ? connector.reason().name() : ex.getClass().getSimpleName();
    }

    private static String finish(UUID memberId, String result) {
        LOG.info("Google connect callback ended with {}{}", result, memberId == null ? "" : " for member " + memberId);
        return result;
    }
}
