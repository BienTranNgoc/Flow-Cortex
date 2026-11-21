package com.flowcortex.connector.infrastructure.telegram;

import com.flowcortex.connector.api.ConnectorException;
import com.flowcortex.connector.api.ConnectorException.Reason;
import com.flowcortex.connector.application.telegram.TelegramBotApi;
import com.flowcortex.connector.application.telegram.TelegramProperties;
import com.flowcortex.connector.infrastructure.OutboundHttp;
import java.net.URI;
import java.util.Map;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link TelegramBotApi} over {@link OutboundHttp}. The bot token lives in the request URI and nowhere else:
 * nothing here logs the URI, and failures leave {@link OutboundHttp} already stripped of it.
 */
@Component
class TelegramBotHttpApi implements TelegramBotApi {

    private static final String CONNECTOR = "telegram";
    private static final String UPDATES_QUERY = "?limit=100&timeout=0";

    private final OutboundHttp http;
    private final TelegramProperties properties;
    private final JsonMapper json = JsonMapper.builder().build();

    TelegramBotHttpApi(OutboundHttp http, TelegramProperties properties) {
        this.http = http;
        this.properties = properties;
    }

    @Override
    public Reply getMe(String token) {
        return lookup(token, "getMe", "");
    }

    @Override
    public Reply getUpdates(String token) {
        return lookup(token, "getUpdates", UPDATES_QUERY);
    }

    @Override
    public Reply send(String token, String method, Map<String, Object> body) {
        OutboundHttp.Request request = new OutboundHttp.Request(HttpMethod.POST, uri(token, method, ""),
                Map.of("Content-Type", "application/json", "Accept", "application/json"),
                json.writeValueAsBytes(body), properties.sendTimeout());
        return reply(http.execute(CONNECTOR, request));
    }

    private Reply lookup(String token, String method, String query) {
        OutboundHttp.Request request = new OutboundHttp.Request(HttpMethod.GET, uri(token, method, query),
                Map.of("Accept", "application/json"), null, properties.readTimeout());
        Reply reply = reply(http.execute(CONNECTOR, request));
        if (!reply.ok() && reply.errorCode() >= 500) {
            throw ConnectorException.of(Reason.UPSTREAM_UNAVAILABLE);
        }
        return reply;
    }

    private URI uri(String token, String method, String query) {
        String base = properties.apiBaseUrl().toString();
        String root = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return URI.create(root + "/bot" + token + "/" + method + query);
    }

    /** A body that is not Telegram's JSON (a proxy error page, a cut body) keeps the HTTP status as the verdict. */
    private Reply reply(OutboundHttp.Response response) {
        boolean httpOk = response.status() >= 200 && response.status() < 300;
        Map<?, ?> body = parse(response.body());
        if (body == null) {
            return new Reply(httpOk, httpOk ? 0 : response.status(), null, null, null);
        }
        boolean ok = Boolean.TRUE.equals(body.get("ok"));
        int code = ok ? 0 : body.get("error_code") instanceof Number n ? n.intValue() : response.status();
        String description = body.get("description") instanceof String s ? s : null;
        Integer retryAfter = body.get("parameters") instanceof Map<?, ?> parameters
                && parameters.get("retry_after") instanceof Number n ? n.intValue() : null;
        return new Reply(ok, code, description, retryAfter, body.get("result"));
    }

    private Map<?, ?> parse(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return json.readValue(body, Map.class);
        } catch (JacksonException ex) {
            return null;
        }
    }
}
