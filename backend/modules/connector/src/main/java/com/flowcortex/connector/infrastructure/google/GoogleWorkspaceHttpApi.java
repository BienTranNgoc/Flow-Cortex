package com.flowcortex.connector.infrastructure.google;

import com.flowcortex.connector.application.google.GoogleErrorRules;
import com.flowcortex.connector.application.google.GoogleProperties;
import com.flowcortex.connector.application.google.GoogleWorkspaceApi;
import com.flowcortex.connector.infrastructure.OutboundHttp;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link GoogleWorkspaceApi} over {@link OutboundHttp}. Tokens and the client secret travel only in a header or a
 * form body, never in a URI, and nothing here logs a URI or a body; a JSON parse failure is thrown without its
 * cause because Jackson quotes the text it could not read.
 */
@Component
class GoogleWorkspaceHttpApi implements GoogleWorkspaceApi {

    private static final String CONNECTOR = "google";
    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() { };
    private static final String FILE_FIELDS = "id,name,mimeType,size,modifiedTime";
    private static final String LIST_FIELDS =
            "nextPageToken,files(id,name,mimeType,size,modifiedTime,owners(emailAddress),webViewLink)";
    private static final String SHEET_FIELDS = "properties.title,sheets.properties(sheetId,title,gridProperties(rowCount,columnCount))";

    private final OutboundHttp http;
    private final GoogleProperties properties;
    private final JsonMapper json = JsonMapper.builder().build();

    GoogleWorkspaceHttpApi(OutboundHttp http, GoogleProperties properties) {
        this.http = http;
        this.properties = properties;
    }

    @Override
    public TokenGrant exchangeCode(String code, String codeVerifier) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("code", code);
        form.put("code_verifier", codeVerifier);
        form.put("client_id", properties.clientId());
        form.put("client_secret", properties.clientSecret());
        form.put("redirect_uri", properties.redirectUri());
        form.put("grant_type", "authorization_code");
        OutboundHttp.Response response = http.execute(CONNECTOR, request(HttpMethod.POST,
                oauth("/token"), form(form), "application/x-www-form-urlencoded"));
        Map<String, Object> body = parse(response);
        if (!ok(response.status())) {
            return new TokenGrant(response.status(), null, null, 0, null, fault(response.status(), body),
                    response.retryAfter());
        }
        return new TokenGrant(response.status(), accessToken(body), string(body, "refresh_token"), expiresIn(body),
                scopes(body), null, response.retryAfter());
    }

    @Override
    public RefreshResult refresh(String refreshToken) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("refresh_token", refreshToken);
        form.put("client_id", properties.clientId());
        form.put("client_secret", properties.clientSecret());
        form.put("grant_type", "refresh_token");
        OutboundHttp.Response response = http.execute(CONNECTOR, request(HttpMethod.POST,
                oauth("/token"), form(form), "application/x-www-form-urlencoded"));
        Map<String, Object> body = parse(response);
        if (!ok(response.status())) {
            return new RefreshResult(response.status(), null, null, 0, null, fault(response.status(), body),
                    response.retryAfter());
        }
        return new RefreshResult(response.status(), accessToken(body), string(body, "refresh_token"),
                expiresIn(body), scopes(body), null, response.retryAfter());
    }

    @Override
    public void revoke(String token) {
        http.execute(CONNECTOR, request(HttpMethod.POST, oauth("/revoke"), form(Map.of("token", token)),
                "application/x-www-form-urlencoded"));
    }

    @Override
    public Reply userinfo(String accessToken) {
        return reply(accessToken, HttpMethod.GET, api("/oauth2/v2/userinfo"), null);
    }

    @Override
    public Reply driveGet(String accessToken, String fileId) {
        return reply(accessToken, HttpMethod.GET,
                api("/drive/v3/files/" + segment(fileId) + query(Map.of("supportsAllDrives", "true",
                        "fields", FILE_FIELDS))), null);
    }

    @Override
    public Reply driveList(String accessToken, String query, String orderBy, int pageSize, String pageToken) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("q", query);
        params.put("orderBy", orderBy);
        params.put("pageSize", Integer.toString(pageSize));
        params.put("supportsAllDrives", "true");
        params.put("includeItemsFromAllDrives", "true");
        params.put("fields", LIST_FIELDS);
        if (pageToken != null && !pageToken.isBlank()) {
            params.put("pageToken", pageToken);
        }
        return reply(accessToken, HttpMethod.GET, api("/drive/v3/files" + query(params)), null);
    }

    @Override
    public BytesReply driveExport(String accessToken, String fileId, String mimeType, int maxBytes) {
        return bytes(accessToken,
                api("/drive/v3/files/" + segment(fileId) + "/export" + query(Map.of("mimeType", mimeType))),
                maxBytes);
    }

    @Override
    public BytesReply driveDownload(String accessToken, String fileId, int maxBytes) {
        return bytes(accessToken, api("/drive/v3/files/" + segment(fileId)
                + query(Map.of("alt", "media", "supportsAllDrives", "true"))), maxBytes);
    }

    @Override
    public Reply sheetsGet(String accessToken, String spreadsheetId) {
        return reply(accessToken, HttpMethod.GET,
                sheets("/v4/spreadsheets/" + segment(spreadsheetId) + query(Map.of("fields", SHEET_FIELDS))), null);
    }

    @Override
    public Reply valuesGet(String accessToken, String spreadsheetId, String range) {
        return reply(accessToken, HttpMethod.GET, sheets("/v4/spreadsheets/" + segment(spreadsheetId) + "/values/"
                + segment(range) + query(Map.of("valueRenderOption", "FORMATTED_VALUE"))), null);
    }

    @Override
    public Reply valuesAppend(String accessToken, String spreadsheetId, String range, List<List<String>> values) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("majorDimension", "ROWS");
        body.put("values", values);
        Map<String, String> params = new LinkedHashMap<>();
        params.put("valueInputOption", "USER_ENTERED");
        params.put("insertDataOption", "INSERT_ROWS");
        return reply(accessToken, HttpMethod.POST, sheets("/v4/spreadsheets/" + segment(spreadsheetId) + "/values/"
                + segment(range) + ":append" + query(params)), json.writeValueAsBytes(body));
    }

    private Reply reply(String accessToken, HttpMethod method, URI uri, byte[] body) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + accessToken);
        headers.put("Accept", "application/json");
        if (body != null) {
            headers.put("Content-Type", "application/json");
        }
        OutboundHttp.Response response = http.execute(CONNECTOR,
                new OutboundHttp.Request(method, uri, headers, body, properties.readTimeout()));
        Map<String, Object> parsed = parse(response);
        return new Reply(response.status(), parsed, fault(response.status(), parsed), response.retryAfter());
    }

    private BytesReply bytes(String accessToken, URI uri, int maxBytes) {
        OutboundHttp.Request request = new OutboundHttp.Request(HttpMethod.GET, uri,
                Map.of("Authorization", "Bearer " + accessToken), null, properties.readTimeout());
        OutboundHttp.BytesResponse response = http.executeBytes(CONNECTOR, request, maxBytes);
        Fault fault = null;
        if (response.status() >= 400) {
            if (response.overLimit()) {
                throw GoogleErrorRules.unparseable();
            }
            fault = fault(response.status(), parse(new String(response.body(), StandardCharsets.UTF_8)));
        }
        return new BytesReply(response.status(), response.contentType(), response.body(), response.overLimit(),
                fault, response.retryAfter());
    }

    private OutboundHttp.Request request(HttpMethod method, URI uri, byte[] body, String contentType) {
        return new OutboundHttp.Request(method, uri,
                Map.of("Content-Type", contentType, "Accept", "application/json"), body, properties.readTimeout());
    }

    private Map<String, Object> parse(OutboundHttp.Response response) {
        if (response.truncated()) {
            throw GoogleErrorRules.unparseable();
        }
        return parse(response.body());
    }

    /** An empty answer is an empty object; anything else must be one JSON object. */
    private Map<String, Object> parse(String body) {
        if (body == null || body.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = json.readValue(body, OBJECT);
            return parsed == null ? Map.of() : parsed;
        } catch (JacksonException ex) {
            throw GoogleErrorRules.unparseable();
        }
    }

    /**
     * Google's first error: the OAuth shape {@code {"error": "invalid_grant", "error_description": …}} or the API
     * shape {@code {"error": {"message", "errors": [{"reason", "domain"}], "details": [{"reason"}], "status"}}}.
     */
    private static Fault fault(int status, Map<String, Object> body) {
        if (status < 400) {
            return null;
        }
        Object error = body.get("error");
        if (error instanceof String code) {
            return new Fault(code, null, string(body, "error_description"));
        }
        if (!(error instanceof Map<?, ?> api)) {
            return null;
        }
        Map<?, ?> first = firstMap(api.get("errors"));
        String reason = first == null ? null : text(first.get("reason"));
        String domain = first == null ? null : text(first.get("domain"));
        if (reason == null) {
            Map<?, ?> detail = firstMap(api.get("details"));
            reason = detail == null ? null : text(detail.get("reason"));
        }
        if (reason == null) {
            reason = text(api.get("status"));
        }
        return new Fault(reason, domain, text(api.get("message")));
    }

    private static Map<?, ?> firstMap(Object list) {
        return list instanceof List<?> items && !items.isEmpty() && items.getFirst() instanceof Map<?, ?> map
                ? map : null;
    }

    private static String accessToken(Map<String, Object> body) {
        String token = string(body, "access_token");
        if (token == null || token.isBlank()) {
            throw GoogleErrorRules.unparseable();
        }
        return token;
    }

    private static long expiresIn(Map<String, Object> body) {
        if (body.get("expires_in") instanceof Number seconds) {
            return seconds.longValue();
        }
        throw GoogleErrorRules.unparseable();
    }

    private static List<String> scopes(Map<String, Object> body) {
        String scope = string(body, "scope");
        return scope == null || scope.isBlank() ? List.of() : List.of(scope.trim().split("\\s+"));
    }

    private static String string(Map<String, Object> body, String key) {
        return text(body.get(key));
    }

    private static String text(Object value) {
        return value instanceof String text ? text : null;
    }

    private static boolean ok(int status) {
        return status >= 200 && status < 300;
    }

    private URI oauth(String path) {
        return uri(properties.oauthBaseUrl(), path);
    }

    private URI api(String path) {
        return uri(properties.apiBaseUrl(), path);
    }

    private URI sheets(String path) {
        return uri(properties.sheetsBaseUrl(), path);
    }

    private static URI uri(URI base, String path) {
        String root = base.toString();
        return URI.create((root.endsWith("/") ? root.substring(0, root.length() - 1) : root) + path);
    }

    private static String segment(String value) {
        return UriUtils.encodePathSegment(value, StandardCharsets.UTF_8);
    }

    private static String query(Map<String, String> params) {
        return "?" + pairs(params);
    }

    private static byte[] form(Map<String, String> params) {
        return pairs(params).getBytes(StandardCharsets.UTF_8);
    }

    private static String pairs(Map<String, String> params) {
        List<String> pairs = new ArrayList<>();
        params.forEach((name, value) -> pairs.add(encode(name) + "=" + encode(value)));
        return String.join("&", pairs);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
