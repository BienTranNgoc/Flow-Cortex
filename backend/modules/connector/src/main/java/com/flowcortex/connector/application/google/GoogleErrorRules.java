package com.flowcortex.connector.application.google;

import com.flowcortex.connector.api.ConnectorException;
import com.flowcortex.connector.api.ConnectorException.Reason;
import com.flowcortex.shared.web.LocalizedMessage;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;

/**
 * The one ordered table that turns a Google error answer into a {@link ConnectorException}. Google's own message
 * text is read only to recognise a spreadsheet request made on a file that is not a native Google Sheet and is
 * never put in the failure.
 */
public final class GoogleErrorRules {

    /** Which Google API answered; the OAuth token endpoint shares the table of the others. */
    public enum Api { DRIVE, SHEETS, OAUTH }

    /** What the failed request was about: a Drive folder (being read or listed) or anything else. */
    public enum Item { FOLDER, OTHER }

    public static final String RECONNECT = "reconnect";
    public static final String SCOPE_MISSING = "scope_missing";
    public static final String FOLDER_NO_ACCESS = "folder_no_access";
    public static final String NOT_A_FOLDER = "not_a_folder";
    public static final String NOT_NATIVE_SHEET = "not_native_sheet";
    public static final String NOT_FOUND = "not_found";
    public static final String REJECTED = "rejected";

    private static final Set<String> SCOPE_REASONS = Set.of("insufficientPermissions", "ACCESS_TOKEN_SCOPE_INSUFFICIENT");
    private static final Set<String> QUOTA_REASONS = Set.of("userRateLimitExceeded", "rateLimitExceeded",
            "dailyLimitExceeded", "sharingRateLimitExceeded");
    private static final String QUOTA_DOMAIN = "usageLimits";
    private static final String NOT_SUPPORTED_TEXT = "not supported for this document";

    private GoogleErrorRules() {
    }

    /**
     * The failure for an answer with {@code status}, Google's first error {@code reason} and {@code domain}, its
     * {@code message} (any of the three may be {@code null}) and the wait it asked for.
     */
    public static ConnectorException classify(Api api, Item item, int status, String reason, String domain,
            String message, Duration retryAfter) {
        if (status == 401) {
            return reconnect();
        }
        if (status == 403 && reason != null && SCOPE_REASONS.contains(reason)) {
            return refused(new LocalizedMessage("connector.google.scopeMissing"), SCOPE_MISSING);
        }
        boolean quotaReason = reason != null && QUOTA_REASONS.contains(reason) || QUOTA_DOMAIN.equals(domain);
        if (status == 429 || (status == 403 && quotaReason)) {
            return new ConnectorException(Reason.RATE_LIMITED, true,
                    new LocalizedMessage("connector.google.rateLimited"), null, retryAfter);
        }
        if (status == 404 || status == 403) {
            return item == Item.FOLDER
                    ? refused(new LocalizedMessage("connector.google.folderNoAccess"), FOLDER_NO_ACCESS)
                    : refused(new LocalizedMessage("connector.google.notFound"), NOT_FOUND);
        }
        if (status == 400 && api == Api.SHEETS && message != null
                && message.toLowerCase(Locale.ROOT).contains(NOT_SUPPORTED_TEXT)) {
            return refused(new LocalizedMessage("connector.google.notNativeSheet"), NOT_NATIVE_SHEET);
        }
        if (status >= 400 && status < 500) {
            return refused(new LocalizedMessage("connector.google.rejected"), REJECTED);
        }
        return new ConnectorException(Reason.UPSTREAM_UNAVAILABLE, false,
                new LocalizedMessage("connector.upstreamUnavailable"), null, null);
    }

    /** A JSON answer that was cut or is not JSON. */
    public static ConnectorException unparseable() {
        return new ConnectorException(Reason.UPSTREAM_UNAVAILABLE, false,
                new LocalizedMessage("connector.upstreamUnavailable"), null, null);
    }

    /** The grant is gone or no longer accepted: the member must connect Google again. */
    public static ConnectorException reconnect() {
        return refused(new LocalizedMessage("connector.google.reconnect"), RECONNECT);
    }

    /** {@link #reconnect()} as the failure of a connection that is no longer usable ({@code NOT_CONNECTED}). */
    public static ConnectorException reconnectNotConnected() {
        return new ConnectorException(Reason.NOT_CONNECTED, false, new LocalizedMessage("connector.google.reconnect"),
                RECONNECT, null);
    }

    private static ConnectorException refused(LocalizedMessage detail, String providerReason) {
        return new ConnectorException(Reason.REFUSED, false, detail, providerReason, null);
    }
}
