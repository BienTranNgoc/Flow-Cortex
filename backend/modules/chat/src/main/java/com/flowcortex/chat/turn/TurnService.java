package com.flowcortex.chat.turn;

import com.flowcortex.chat.cards.Attachment;
import com.flowcortex.chat.config.ChatProperties;
import com.flowcortex.chat.config.ChatSwitch;
import com.flowcortex.chat.conversation.ConversationStore;
import com.flowcortex.chat.conversation.ConversationStore.StoredConversation;
import com.flowcortex.chat.conversation.ConversationStore.StoredMessage;
import com.flowcortex.chat.quota.ChatQuota;
import com.flowcortex.chat.turn.TurnStore.AnswerRow;
import com.flowcortex.chat.turn.TurnStore.Claim;
import com.flowcortex.chat.web.ChatProblemException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Starts, stops and retries turns. A send is one database transaction: idempotency claim, message numbers,
 * member message, the answer row (the partial unique index refuses a second running turn), then the switch
 * and the quota charge, so a refused send stores nothing and charges nothing. The turn is handed to the
 * runner only after that commit.
 */
@Service("chatTurnService")
public class TurnService {

    static final Duration IDEMPOTENCY_WINDOW = Duration.ofHours(24);
    static final int TITLE_CHARS = 60;

    private static final Logger LOG = LoggerFactory.getLogger(TurnService.class);

    private final TurnStore store;
    private final ConversationStore conversations;
    private final TurnRunner runner;
    private final ChatQuota quota;
    private final ChatSwitch chatSwitch;
    private final ChatTelemetry telemetry;
    private final ChatProperties properties;
    private final MessageSource messages;
    private final JsonMapper jsonMapper;
    private final Clock clock;
    private final TransactionTemplate transactions;

    TurnService(TurnStore store, ConversationStore conversations, TurnRunner runner, ChatQuota quota,
            ChatSwitch chatSwitch, ChatTelemetry telemetry, ChatProperties properties, MessageSource messages,
            JsonMapper jsonMapper, Clock clock, PlatformTransactionManager transactionManager) {
        this.store = store;
        this.conversations = conversations;
        this.runner = runner;
        this.quota = quota;
        this.chatSwitch = chatSwitch;
        this.telemetry = telemetry;
        this.properties = properties;
        this.messages = messages;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /** The member message of a send and the answer row started for it. */
    public record Started(StoredMessage userMessage, UUID assistantMessageId) {
    }

    public record CreatedConversation(StoredConversation conversation, Started first) {
    }

    private record Attempt(Started started, TurnJob job, UUID conversationId) {
    }

    /** {@code language} is the request language at send time ({@code vi} or {@code en}). */
    public Started send(UUID ownerId, UUID conversationId, String key, String text, List<Attachment> attachments,
            String language) {
        String hash = fingerprint("send", conversationId.toString(), text, attachments);
        UUID assistantId = UUID.randomUUID();
        return execute(ownerId, charged -> {
            Optional<Attempt> replay = replayOf(ownerId, key, hash, conversationId, assistantId);
            return replay.orElseGet(() -> begin(ownerId, conversationId, assistantId, text, attachments, hash,
                    language, true, charged));
        }).started();
    }

    /**
     * Creates a conversation and sends its first message in one transaction. {@code title} is null to name it
     * after the message; a refused send leaves no conversation.
     */
    public CreatedConversation create(UUID ownerId, String title, String key, String text,
            List<Attachment> attachments, String language) {
        UUID conversationId = UUID.randomUUID();
        UUID assistantId = UUID.randomUUID();
        String hash = fingerprint("create", title == null ? "" : title, text, attachments);
        Attempt attempt = execute(ownerId, charged -> {
            Optional<Attempt> replay = replayOf(ownerId, key, hash, conversationId, assistantId);
            if (replay.isPresent()) {
                return replay.get();
            }
            conversations.create(ownerId, conversationId, title == null ? titleOf(text) : title);
            return begin(ownerId, conversationId, assistantId, text, attachments, hash, language, false, charged);
        });
        StoredConversation conversation = conversations.find(ownerId, attempt.conversationId())
                .orElseThrow(ChatProblemException::conversationNotFound);
        return new CreatedConversation(conversation, attempt.started());
    }

    /**
     * Starts a new answer to the member message behind a failed or stopped one; it is charged again. Only the
     * conversation's latest message can be retried: a later message supersedes it.
     */
    public Started retry(UUID ownerId, UUID conversationId, UUID answerId, String key, String language) {
        String hash = fingerprint("retry", conversationId.toString(), answerId.toString(), null);
        UUID assistantId = UUID.randomUUID();
        return execute(ownerId, charged -> {
            Optional<Attempt> replay = replayOf(ownerId, key, hash, conversationId, assistantId);
            if (replay.isPresent()) {
                return replay.get();
            }
            if (!store.conversationOwned(ownerId, conversationId)) {
                throw ChatProblemException.conversationNotFound();
            }
            AnswerRow answer = store.answerRow(conversationId, answerId)
                    .orElseThrow(ChatProblemException::messageNotFound);
            StoredMessage user = store.userMessageBefore(conversationId, answer.seq())
                    .orElseThrow(ChatProblemException::turnNotRetryable);
            boolean retryable = "assistant".equals(answer.role()) && "text".equals(answer.kind())
                    && ("failed".equals(answer.status()) || "stopped".equals(answer.status()));
            if (!retryable || store.hasMessageAfter(conversationId, answer.seq())) {
                throw ChatProblemException.turnNotRetryable();
            }
            int seq = store.allocateSeq(ownerId, conversationId, 1)
                    .orElseThrow(ChatProblemException::conversationNotFound);
            return answerFor(ownerId, conversationId, assistantId, seq, user, hash, language, charged);
        }).started();
    }

    /** Asks a running turn to stop; a turn that already ended is left as it is. */
    public void stop(UUID ownerId, UUID conversationId, UUID answerId) {
        if (!store.conversationOwned(ownerId, conversationId)) {
            throw ChatProblemException.conversationNotFound();
        }
        if (store.requestStop(conversationId, answerId) == 0 && store.answerRow(conversationId, answerId).isEmpty()) {
            throw ChatProblemException.messageNotFound();
        }
    }

    private Attempt execute(UUID ownerId, Function<AtomicReference<LocalDate>, Attempt> work) {
        if (!chatSwitch.available()) {
            telemetry.refused("chat-disabled");
            throw ChatProblemException.disabled();
        }
        AtomicReference<LocalDate> charged = new AtomicReference<>();
        Attempt attempt;
        try {
            attempt = transactions.execute(status -> work.apply(charged));
        } catch (RuntimeException e) {
            giveBackCharge(ownerId, charged.get());
            if (e instanceof ChatProblemException problem) {
                telemetry.refused(problem.code());
            }
            throw e;
        }
        if (attempt.job() != null) {
            runner.start(attempt.job());
        }
        return attempt;
    }

    private void giveBackCharge(UUID ownerId, LocalDate day) {
        if (day == null) {
            return;
        }
        try {
            quota.refund(ownerId, day);
        } catch (RuntimeException e) {
            LOG.error("chat.send outcome=refund_failed", e);
        }
    }

    /** The result already stored for the key; empty when this send claimed it. */
    private Optional<Attempt> replayOf(UUID ownerId, String key, String hash, UUID conversationId,
            UUID assistantId) {
        if (store.claimKey(ownerId, key, hash, conversationId, assistantId,
                clock.instant().minus(IDEMPOTENCY_WINDOW))) {
            return Optional.empty();
        }
        Claim claim = store.claim(ownerId, key).orElseThrow(
                () -> new IllegalStateException("chat_idempotency row vanished while it was read"));
        if (!claim.requestHash().equals(hash)) {
            throw ChatProblemException.idempotencyKeyReused();
        }
        StoredMessage answer = store.message(claim.assistantId())
                .orElseThrow(ChatProblemException::conversationNotFound);
        StoredMessage user = store.userMessageBefore(claim.conversationId(), answer.seq())
                .orElseThrow(ChatProblemException::conversationNotFound);
        return Optional.of(new Attempt(new Started(user, answer.id()), null, claim.conversationId()));
    }

    private Attempt begin(UUID ownerId, UUID conversationId, UUID assistantId, String text,
            List<Attachment> attachments, String hash, String language, boolean titleFromFirstMessage, AtomicReference<LocalDate> charged) {
        int userSeq = store.allocateSeq(ownerId, conversationId, 2)
                .orElseThrow(ChatProblemException::conversationNotFound);
        boolean firstMessage = !store.hasUserMessage(conversationId);
        UUID userId = UUID.randomUUID();
        store.insertUserMessage(userId, conversationId, userSeq, text, attachmentPayload(attachments), hash);
        StoredMessage user = store.message(userId).orElseThrow();
        Attempt attempt = answerFor(ownerId, conversationId, assistantId, userSeq + 1, user, hash, language,
                charged);
        if (titleFromFirstMessage && firstMessage) {
            store.retitleDefault(conversationId, titleOf(text),
                    messages.getMessage("chat.conversation.untitled", null, Locale.ENGLISH),
                    messages.getMessage("chat.conversation.untitled", null, Locale.forLanguageTag("vi")));
        }
        return attempt;
    }

    /** Inserts the running answer row for {@code user}, then charges: a refusal of either stores nothing. */
    private Attempt answerFor(UUID ownerId, UUID conversationId, UUID assistantId, int answerSeq, StoredMessage user,
            String hash, String language, AtomicReference<LocalDate> charged) {
        Instant deadline = clock.instant().plus(properties.turnTimeout());
        store.insertAssistant(assistantId, conversationId, answerSeq, deadline, hash);
        LocalDate day = quota.charge(ownerId);
        charged.set(day);
        store.setChargeDay(assistantId, day);
        TurnJob job = new TurnJob(conversationId, ownerId, assistantId, user.seq(), language, day, deadline);
        return new Attempt(new Started(user, assistantId), job, conversationId);
    }

    private String attachmentPayload(List<Attachment> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return null;
        }
        try {
            return jsonMapper.writeValueAsString(Map.of("attachments", attachments));
        } catch (JacksonException e) {
            throw new IllegalStateException("attachments are not serializable", e);
        }
    }

    static String titleOf(String text) {
        String line = text.strip().replaceAll("\\R", " ");
        int end = line.offsetByCodePoints(0, Math.min(TITLE_CHARS, line.codePointCount(0, line.length())));
        return line.substring(0, end);
    }

    private static String fingerprint(String operation, String scope, String text, List<Attachment> attachments) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String joined = String.join("\u0000", operation, scope, text == null ? "" : text,
                    attachments == null ? "" : String.join(",", attachments.stream()
                            .map(a -> a.uploadId() + "=" + a.fileName()).toList()));
            return HexFormat.of().formatHex(digest.digest(joined.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
