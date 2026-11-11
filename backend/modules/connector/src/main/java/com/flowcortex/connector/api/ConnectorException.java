package com.flowcortex.connector.api;

import com.flowcortex.shared.web.LocalizedMessage;
import java.time.Duration;
import java.util.Objects;

/**
 * The one failure a connector call or send surfaces. Runtime callers branch on {@link #reason()} and
 * {@link #retryable()}, never on an HTTP status; the web layer maps the reason to a status and the
 * {@link #detail()} key. {@code toString} and the message carry no token, URL or upstream text.
 */
public class ConnectorException extends RuntimeException {

    /** Why the call failed; the web layer owns the status of each. */
    public enum Reason {
        SUSPENDED, TURNED_OFF, NOT_CONNECTED, USAGE_DISABLED, CREDENTIAL_UNREADABLE, INVALID_INPUT, REFUSED,
        RATE_LIMITED, UPSTREAM_UNAVAILABLE, OUT_OF_BASE_URL
    }

    private final Reason reason;
    private final boolean retryable;
    private final LocalizedMessage detail;
    private final String telegramReason;
    private final Duration retryAfter;

    public ConnectorException(Reason reason, boolean retryable, LocalizedMessage detail, String telegramReason,
            Duration retryAfter) {
        super(reason + " " + Objects.requireNonNull(detail, "detail").code());
        this.reason = Objects.requireNonNull(reason, "reason");
        this.retryable = retryable;
        this.detail = detail;
        this.telegramReason = telegramReason;
        this.retryAfter = retryAfter;
    }

    /** A failure that no retry can cure and that has no Telegram reason or wait. */
    public ConnectorException(Reason reason, LocalizedMessage detail) {
        this(reason, false, detail, null, null);
    }

    /** The failure of a reason whose message does not depend on the call. */
    public static ConnectorException of(Reason reason) {
        return switch (reason) {
            case SUSPENDED -> new ConnectorException(reason, new LocalizedMessage("problem.forbidden"));
            case TURNED_OFF -> new ConnectorException(reason, new LocalizedMessage("feature.turnedOff"));
            case NOT_CONNECTED -> new ConnectorException(reason, new LocalizedMessage("connector.notConnected"));
            case USAGE_DISABLED -> new ConnectorException(reason, new LocalizedMessage("connector.usageDisabled"));
            case CREDENTIAL_UNREADABLE ->
                    new ConnectorException(reason, new LocalizedMessage("connector.credentialUnreadable"));
            case UPSTREAM_UNAVAILABLE ->
                    new ConnectorException(reason, true, new LocalizedMessage("connector.upstreamUnavailable"), null,
                            null);
            case INVALID_INPUT, REFUSED, RATE_LIMITED, OUT_OF_BASE_URL ->
                    throw new IllegalArgumentException(reason + " needs a message of its own");
        };
    }

    public Reason reason() {
        return reason;
    }

    /** Whether the same call may succeed later: nothing was written upstream, or the upstream asked to wait. */
    public boolean retryable() {
        return retryable;
    }

    public LocalizedMessage detail() {
        return detail;
    }

    /** The Telegram refusal reason of a {@link Reason#REFUSED} send, else {@code null}. */
    public String telegramReason() {
        return telegramReason;
    }

    /** How long the upstream asked to wait, else {@code null}. */
    public Duration retryAfter() {
        return retryAfter;
    }

    @Override
    public String toString() {
        return "ConnectorException[reason=" + reason + ", retryable=" + retryable + "]";
    }
}
