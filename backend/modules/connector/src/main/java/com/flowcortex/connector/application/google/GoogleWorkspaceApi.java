package com.flowcortex.connector.application.google;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Google's OAuth, Drive and Sheets endpoints as the connector uses them, with no rule of its own. Every method
 * returns what Google answered, an error status included, with Google's first error cut to a {@link Fault};
 * services above classify it through {@link GoogleErrorRules}. A failure to reach Google, a cut JSON answer or an
 * answer that is not JSON is thrown as {@code UPSTREAM_UNAVAILABLE}. No result prints a token in {@code toString}.
 */
public interface GoogleWorkspaceApi {

    /** Google's first error: its {@code reason}, {@code domain} and {@code message}, any of which may be {@code null}. */
    record Fault(String reason, String domain, String message) {
    }

    /**
     * The answer of the code exchange. On success {@code accessToken} and {@code expiresInSeconds} are set,
     * {@code refreshToken} only when Google issued one; on a refusal {@code fault} says why.
     */
    record TokenGrant(int status, String accessToken, String refreshToken, long expiresInSeconds,
            List<String> scopes, Fault fault, Duration retryAfter) {

        public boolean ok() {
            return status >= 200 && status < 300;
        }

        @Override
        public String toString() {
            return "TokenGrant[status=" + status + ", scopes=" + scopes + "]";
        }
    }

    /** The answer of a refresh: as {@link TokenGrant}, where {@code refreshToken} is set only when Google rotated it. */
    record RefreshResult(int status, String accessToken, String refreshToken, long expiresInSeconds,
            List<String> scopes, Fault fault, Duration retryAfter) {

        public boolean ok() {
            return status >= 200 && status < 300;
        }

        @Override
        public String toString() {
            return "RefreshResult[status=" + status + "]";
        }
    }

    /** A JSON answer: {@code body} is empty for an empty answer, {@code fault} is set from status 400 on when Google gave one. */
    record Reply(int status, Map<String, Object> body, Fault fault, Duration retryAfter) {

        public boolean ok() {
            return status >= 200 && status < 300;
        }

        @Override
        public String toString() {
            return "Reply[status=" + status + "]";
        }
    }

    /**
     * A non-JSON answer read up to a byte limit: {@code body} holds at most that many bytes and {@code overLimit}
     * says there were more. From status 400 on {@code fault} is read from the body.
     */
    record BytesReply(int status, String contentType, byte[] body, boolean overLimit, Fault fault,
            Duration retryAfter) {

        public boolean ok() {
            return status >= 200 && status < 300;
        }

        @Override
        public String toString() {
            return "BytesReply[status=" + status + ", bytes=" + body.length + ", overLimit=" + overLimit + "]";
        }
    }

    TokenGrant exchangeCode(String code, String codeVerifier);

    RefreshResult refresh(String refreshToken);

    /** Asks Google to revoke a token; the answer is not needed. */
    void revoke(String token);

    Reply userinfo(String accessToken);

    /** {@code files.get}: id, name, MIME type, size and modification time, Shared Drives included. */
    Reply driveGet(String accessToken, String fileId);

    /** {@code files.list} over {@code query}, Shared Drives included; {@code pageToken} may be {@code null}. */
    Reply driveList(String accessToken, String query, String orderBy, int pageSize, String pageToken);

    /** {@code files.export} of a Google document as {@code mimeType}, at most {@code maxBytes}. */
    BytesReply driveExport(String accessToken, String fileId, String mimeType, int maxBytes);

    /** {@code files.get} with {@code alt=media}, at most {@code maxBytes}. */
    BytesReply driveDownload(String accessToken, String fileId, int maxBytes);

    /** {@code spreadsheets.get}: the title and each tab's id, title and grid size. */
    Reply sheetsGet(String accessToken, String spreadsheetId);

    /** {@code spreadsheets.values.get} of {@code range} as displayed (formatted values). */
    Reply valuesGet(String accessToken, String spreadsheetId, String range);

    /** {@code spreadsheets.values.append}: typed as by a user, inserting new rows after {@code range}'s data. */
    Reply valuesAppend(String accessToken, String spreadsheetId, String range, List<List<String>> values);
}
