package com.flowcortex.chat.web;

import com.flowcortex.shared.web.LocalizedMessage;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * A chat refusal rendered as RFC 9457 problem {@code https://flow-cortex.dev/problems/<code>}, with
 * optional extra members and field {@code errors}. The detail and every field error are message
 * bundle keys, put into the request's language by {@link ChatProblemHandler}.
 */
public class ChatProblemException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final LocalizedMessage detail;
    private final Map<String, Object> properties = new LinkedHashMap<>();
    private final Map<String, LocalizedMessage> fieldErrors = new LinkedHashMap<>();

    public ChatProblemException(HttpStatus status, String code, LocalizedMessage detail) {
        super(detail.toString());
        this.status = status;
        this.code = code;
        this.detail = detail;
    }

    public static ChatProblemException conversationNotFound() {
        return new ChatProblemException(HttpStatus.NOT_FOUND, "chat-conversation-not-found",
                new LocalizedMessage("chat.conversation.notFound"));
    }

    public static ChatProblemException messageNotFound() {
        return new ChatProblemException(HttpStatus.NOT_FOUND, "chat-message-not-found",
                new LocalizedMessage("chat.message.notFound"));
    }

    public static ChatProblemException disabled() {
        return new ChatProblemException(HttpStatus.SERVICE_UNAVAILABLE, "chat-disabled",
                new LocalizedMessage("chat.disabled"));
    }

    public static ChatProblemException turnInProgress() {
        return new ChatProblemException(HttpStatus.CONFLICT, "chat-turn-in-progress",
                new LocalizedMessage("chat.turn.inProgress"));
    }

    public static ChatProblemException turnNotRetryable() {
        return new ChatProblemException(HttpStatus.CONFLICT, "chat-turn-not-retryable",
                new LocalizedMessage("chat.turn.notRetryable"));
    }

    public static ChatProblemException idempotencyKeyReused() {
        return new ChatProblemException(HttpStatus.UNPROCESSABLE_CONTENT, "idempotency-key-reused",
                new LocalizedMessage("chat.idempotency.reused"));
    }

    /** 400 {@code chat-card-invalid}; {@code errors} maps each refused card field to its message. */
    public static ChatProblemException cardInvalid(Map<String, LocalizedMessage> errors) {
        return new ChatProblemException(HttpStatus.BAD_REQUEST, "chat-card-invalid",
                new LocalizedMessage("chat.card.invalid")).withFieldErrors(errors);
    }

    /** 409 {@code chat-card-images-expired}: an image of the card is gone, so the member attaches new ones. */
    public static ChatProblemException cardImagesExpired() {
        return new ChatProblemException(HttpStatus.CONFLICT, "chat-card-images-expired",
                new LocalizedMessage("chat.card.imagesExpired"));
    }

    /** 429 {@code chat-quota} for a day limit; {@code resumeAt} is the next 00:00 Asia/Ho_Chi_Minh. */
    public static ChatProblemException quotaExhausted(Instant resumeAt) {
        return new ChatProblemException(HttpStatus.TOO_MANY_REQUESTS, "chat-quota",
                new LocalizedMessage("chat.quota.exhausted")).with("resumeAt", resumeAt);
    }

    /** 429 {@code chat-quota} for the per-minute limit; {@code resumeAt} is when the next send is allowed. */
    public static ChatProblemException rateLimited(Instant resumeAt) {
        return new ChatProblemException(HttpStatus.TOO_MANY_REQUESTS, "chat-quota",
                new LocalizedMessage("chat.quota.perMinute")).with("resumeAt", resumeAt);
    }

    public ChatProblemException with(String name, Object value) {
        properties.put(name, value);
        return this;
    }

    public ChatProblemException withFieldErrors(Map<String, LocalizedMessage> errors) {
        fieldErrors.putAll(errors);
        return this;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public LocalizedMessage detail() {
        return detail;
    }

    public Map<String, Object> properties() {
        return Collections.unmodifiableMap(properties);
    }

    public Map<String, LocalizedMessage> fieldErrors() {
        return Collections.unmodifiableMap(fieldErrors);
    }
}
