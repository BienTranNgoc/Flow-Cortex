package com.flowcortex.connector.application.http;

import com.flowcortex.connector.api.ConnectorConnection;
import com.flowcortex.connector.api.ConnectorCredentials;
import com.flowcortex.connector.api.ConnectorException;
import com.flowcortex.connector.api.ConnectorException.Reason;
import com.flowcortex.connector.api.ConnectorTokens;
import com.flowcortex.connector.api.HttpConnector;
import com.flowcortex.connector.api.HttpConnectorRequest;
import com.flowcortex.connector.api.HttpConnectorResponse;
import com.flowcortex.connector.application.ConnectorAccess;
import com.flowcortex.connector.application.http.HttpConnectorRules.Capped;
import com.flowcortex.connector.application.http.HttpConnectorRules.Connection;
import com.flowcortex.connector.application.http.HttpConnectorRules.Prepared;
import com.flowcortex.connector.infrastructure.OutboundHttp;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Saves, lists and calls a member's HTTP connectors; every call goes through the shared usage gate. */
@Service
public class HttpConnectorService implements HttpConnector {

    private static final String KIND = "http";
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(30);

    /** A save as the member typed it; {@code toString} omits the token and the extra-header values. */
    public record SaveCommand(String key, String label, String authType, String token, String baseUrl,
            String headerName, String paramName, Map<String, String> extraHeaders) {

        @Override
        public String toString() {
            return "SaveCommand[key=" + key + "]";
        }
    }

    /** What a save answers. */
    public record Saved(String key, String label, String authType, String baseUrl) {
    }

    /** One listed connector: names only, no token and no extra-header value. */
    public record Summary(String key, String label, String authType, String baseUrl, String headerName,
            String paramName, List<String> extraHeaderNames) {
    }

    private final ConnectorAccess access;
    private final ConnectorCredentials credentials;
    private final OutboundHttp outbound;

    HttpConnectorService(ConnectorAccess access, ConnectorCredentials credentials, OutboundHttp outbound) {
        this.access = access;
        this.credentials = credentials;
        this.outbound = outbound;
    }

    /** Validates and stores the connector, replacing a saved one of the same key. */
    public Saved save(UUID memberId, SaveCommand command) {
        access.requireMember(memberId, true);
        String key = HttpConnectorRules.normalizeKey(command.key()).orElseThrow(HttpConnectorRules::keyInvalid);
        Connection connection = HttpConnectorRules.connection(command.baseUrl(), command.authType(),
                command.token(), command.headerName(), command.paramName());
        Map<String, String> extraHeaders = HttpConnectorRules.extraHeaders(command.extraHeaders(), connection);
        credentials.find(memberId, key).ifPresent(existing -> {
            if (!KIND.equals(existing.metadata().get("kind"))) {
                throw HttpConnectorRules.keyInvalid();
            }
        });
        String label = HttpConnectorRules.label(command.label(), key);
        String secret = HttpSecret.encode(command.token().trim(), extraHeaders);
        credentials.connect(memberId, key, new ConnectorTokens(secret, null, null, List.of()),
                HttpConnectorRules.metadata(label, connection, extraHeaders));
        return new Saved(key, label, connection.authType(), connection.baseUrl());
    }

    /** The member's active HTTP connectors, ordered by key. */
    public List<Summary> list(UUID memberId) {
        return credentials.list(memberId, false).stream()
                .filter(HttpConnectorService::isHttp)
                .map(HttpConnectorService::summary)
                .sorted(Comparator.comparing(Summary::key))
                .toList();
    }

    @Override
    public HttpConnectorResponse call(UUID memberId, HttpConnectorRequest request) {
        String key = HttpConnectorRules.normalizeKey(request.key()).orElse(null);
        if (key == null) {
            access.requireMember(memberId, true);
            throw ConnectorException.of(Reason.NOT_CONNECTED);
        }
        ConnectorAccess.Granted granted = access.use(memberId, key, true);
        if (!isHttp(granted.connection())) {
            throw ConnectorException.of(Reason.NOT_CONNECTED);
        }
        Connection connection = connection(granted.connection());
        HttpSecret.Decoded secret = HttpSecret.decode(granted.tokens().accessToken());
        Prepared prepared = HttpConnectorRules.prepare(connection, secret, request);
        OutboundHttp.Response response = outbound.execute(key, new OutboundHttp.Request(prepared.method(),
                prepared.uri(), prepared.headers(), prepared.body(), CALL_TIMEOUT));
        Capped body = HttpConnectorRules.cap(response.body(), response.truncated());
        return new HttpConnectorResponse(response.status(), response.status() >= 200 && response.status() < 300,
                response.contentType(), body.text(), body.truncated());
    }

    private static boolean isHttp(ConnectorConnection connection) {
        return KIND.equals(connection.metadata().get("kind"));
    }

    private static Summary summary(ConnectorConnection connection) {
        Map<String, Object> metadata = connection.metadata();
        return new Summary(connection.provider(), text(metadata, "label", connection.provider()),
                text(metadata, "authType", null), text(metadata, "baseUrl", null), text(metadata, "headerName", null),
                text(metadata, "paramName", null), HttpConnectorRules.names(metadata.get("extraHeaderNames")));
    }

    /** The stored shape of a connector; a row missing a field the call needs is an unreadable credential. */
    private static Connection connection(ConnectorConnection stored) {
        Map<String, Object> metadata = stored.metadata();
        String baseUrl = text(metadata, "baseUrl", null);
        String authType = text(metadata, "authType", null);
        String headerName = text(metadata, "headerName", null);
        String paramName = text(metadata, "paramName", null);
        boolean complete = baseUrl != null && switch (String.valueOf(authType)) {
            case "bearer" -> true;
            case "header" -> headerName != null;
            case "query" -> paramName != null;
            default -> false;
        };
        if (!complete) {
            throw ConnectorException.of(Reason.CREDENTIAL_UNREADABLE);
        }
        return new Connection(baseUrl, authType, headerName, paramName);
    }

    private static String text(Map<String, Object> metadata, String field, String fallback) {
        return metadata.get(field) instanceof String value ? value : fallback;
    }
}
