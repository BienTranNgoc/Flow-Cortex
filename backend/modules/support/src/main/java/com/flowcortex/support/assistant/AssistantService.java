package com.flowcortex.support.assistant;

import com.flowcortex.llm.api.LlmJsonResult;
import com.flowcortex.support.assistant.ConversationStore.StoredConversation;
import com.flowcortex.support.assistant.ConversationStore.StoredMessage;
import com.flowcortex.support.config.AssistantSwitch;
import com.flowcortex.support.config.SupportProperties;
import com.flowcortex.support.kb.KnowledgeBaseService.ScoredArticle;
import com.flowcortex.support.web.SupportProblemException;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Answers a question from the published articles retrieved for it. The provider is called only when
 * the assistant is on, the question is valid, articles matched and every quota had room; a question
 * the provider could not answer is refunded.
 */
@Service
class AssistantService {

    static final int MAX_QUESTION_CHARS = 1000;
    static final String NO_ANSWER = "Mình chưa có câu trả lời cho câu hỏi này";
    static final String REFUSAL = "Mình không thể trả lời câu hỏi này";

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Logger LOG = LoggerFactory.getLogger(AssistantService.class);

    private static final String TARGET = "\\((?:[^()\\s]|\\([^()]*\\))*(?:\\s+\"[^\"]*\")?\\)";
    private static final Pattern IMAGE = Pattern.compile("!\\[([^\\]]*)\\]" + TARGET);
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]*)\\]" + TARGET);
    private static final Pattern LINK_DEFINITION = Pattern.compile("(?m)^ {0,3}\\[[^\\]]+\\]:[ \\t]*\\S+.*$");
    private static final Pattern AUTOLINK = Pattern.compile("<[a-zA-Z][a-zA-Z0-9+.-]{1,31}:[^<>\\s]*>");
    private static final Pattern BARE_URL =
            Pattern.compile("(?i)(?:https?://|ftp://|www\\.)[^\\s<>]*[^\\s<>.,;:!?'\")\\]]");

    private final AssistantSwitch assistantSwitch;
    private final String model;
    private final ConversationStore conversations;
    private final QuotaService quotas;
    private final MinuteLimiter minuteLimiter;
    private final Retriever retriever;
    private final LlmDeadline llm;
    private final AssistantTelemetry telemetry;
    private final Clock clock;

    AssistantService(AssistantSwitch assistantSwitch, SupportProperties properties, ConversationStore conversations,
            QuotaService quotas, MinuteLimiter minuteLimiter, Retriever retriever, LlmDeadline llm,
            AssistantTelemetry telemetry, Clock clock) {
        this.assistantSwitch = assistantSwitch;
        this.model = properties.assistant().model();
        this.conversations = conversations;
        this.quotas = quotas;
        this.minuteLimiter = minuteLimiter;
        this.retriever = retriever;
        this.llm = llm;
        this.telemetry = telemetry;
        this.clock = clock;
    }

    @Schema(name = "SupportAssistantSource")
    record Source(
            @Schema(requiredMode = RequiredMode.REQUIRED) String slug,
            @Schema(requiredMode = RequiredMode.REQUIRED) String title) {
    }

    @Schema(name = "SupportAssistantReplyMessage")
    record ReplyMessage(
            @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = "assistant") String role,
            @Schema(requiredMode = RequiredMode.REQUIRED) String text,
            @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"answered", "no_answer", "blocked"})
            String outcome,
            @Schema(requiredMode = RequiredMode.REQUIRED) List<Source> sources) {
    }

    @Schema(name = "SupportAssistantQuota")
    record QuotaView(
            @Schema(requiredMode = RequiredMode.REQUIRED) int remaining,
            @Schema(requiredMode = RequiredMode.REQUIRED) Instant resetsAt) {
    }

    @Schema(name = "SupportAssistantReply")
    record AssistantReply(
            @Schema(requiredMode = RequiredMode.REQUIRED) UUID conversationId,
            @Schema(requiredMode = RequiredMode.REQUIRED) ReplyMessage message,
            @Schema(requiredMode = RequiredMode.REQUIRED) QuotaView quota) {
    }

    @Schema(name = "SupportAssistantHistoryMessage")
    record HistoryMessage(
            @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"user", "assistant"}) String role,
            @Schema(requiredMode = RequiredMode.REQUIRED) String text,
            @Schema(allowableValues = {"answered", "no_answer", "blocked"}) String outcome,
            @Schema(requiredMode = RequiredMode.REQUIRED) List<Source> sources,
            @Schema(requiredMode = RequiredMode.REQUIRED) Instant createdAt) {
    }

    @Schema(name = "SupportAssistantConversation")
    record ConversationView(
            @Schema(requiredMode = RequiredMode.REQUIRED) UUID conversationId,
            @Schema(requiredMode = RequiredMode.REQUIRED) List<HistoryMessage> messages) {
    }

    record Answer(String outcome, String text, List<ScoredArticle> sources) {
    }

    AssistantReply ask(Asker asker, UUID conversationId, String rawQuestion) {
        long started = System.nanoTime();
        if (!assistantSwitch.available()) {
            telemetry.record("disabled", 0, 0, started);
            throw new SupportProblemException(HttpStatus.SERVICE_UNAVAILABLE, "assistant-disabled",
                    "The assistant is switched off");
        }
        try {
            quotas.refuseWhenPaused();
        } catch (SupportProblemException e) {
            telemetry.record("paused", 0, 0, started);
            throw e;
        }
        String question = validQuestion(rawQuestion);
        if (conversationId != null && !conversations.owns(asker, conversationId)) {
            throw conversationNotFound();
        }
        try {
            minuteLimiter.acquire(asker.subjectKey());
        } catch (SupportProblemException e) {
            telemetry.record("quota", 0, length(question), started);
            throw e;
        }
        Instant askedAt = clock.instant();
        List<ScoredArticle> articles = retriever.retrieve(question);
        if (articles.isEmpty()) {
            QuotaService.Remaining left = quotas.peek(asker);
            return reply(asker, conversationId, question, askedAt, new Answer("no_answer", NO_ANSWER, List.of()),
                    new QuotaView(left.remaining(), left.resetsAt()), started);
        }
        QuotaService.Charge charge;
        try {
            charge = quotas.charge(asker);
        } catch (SupportProblemException e) {
            telemetry.record("assistant-paused".equals(e.code()) ? "paused" : "quota", 0, length(question), started);
            throw e;
        }
        try {
            return answerCharged(asker, conversationId, question, askedAt, articles, charge, started);
        } catch (RuntimeException e) {
            refund(charge, e);
            throw e;
        }
    }

    private AssistantReply answerCharged(Asker asker, UUID conversationId, String question, Instant askedAt,
            List<ScoredArticle> articles, QuotaService.Charge charge, long started) {
        LlmJsonResult result = llm.call(PromptBuilder.request(model, question, articles));
        Optional<Answer> answer = answer(result, articles);
        telemetry.provider(model, result, answer.map(a -> a.sources().size()).orElse(0));
        if (answer.isEmpty()) {
            telemetry.record("unavailable", 0, length(question), started);
            throw new SupportProblemException(HttpStatus.SERVICE_UNAVAILABLE, "assistant-unavailable",
                    "The assistant could not answer right now");
        }
        return reply(asker, conversationId, question, askedAt, answer.get(),
                new QuotaView(charge.remaining(), QuotaService.resetsAt(charge.day())), started);
    }

    private void refund(QuotaService.Charge charge, RuntimeException cause) {
        try {
            quotas.refund(charge);
        } catch (RuntimeException e) {
            LOG.error("support.assistant.refund outcome=failed error={}", e.getClass().getName());
            cause.addSuppressed(e);
        }
    }

    Optional<ConversationView> latest(Asker asker) {
        return conversations.latest(asker).map(this::view);
    }

    static SupportProblemException conversationNotFound() {
        return new SupportProblemException(HttpStatus.NOT_FOUND, "conversation-not-found",
                "No such conversation for this caller");
    }

    private static String validQuestion(String rawQuestion) {
        String question = rawQuestion == null ? "" : rawQuestion.strip();
        if (question.isEmpty()) {
            throw new SupportProblemException(HttpStatus.BAD_REQUEST, "validation-failed",
                    "A question needs 1-" + MAX_QUESTION_CHARS + " characters");
        }
        if (length(question) > MAX_QUESTION_CHARS) {
            throw new SupportProblemException(HttpStatus.BAD_REQUEST, "question-too-long",
                    "A question holds at most " + MAX_QUESTION_CHARS + " characters");
        }
        return question;
    }

    /** Characters as a person counts them, so an emoji is one, not two UTF-16 units. */
    private static int length(String question) {
        return question.codePointCount(0, question.length());
    }

    /**
     * The answer with every Markdown link and image reduced to its text and every URL dropped: the
     * provider could otherwise point outside the retrieved articles, which reach the reader as sources.
     */
    static String withoutLinks(String text) {
        String plain = IMAGE.matcher(text).replaceAll("$1");
        plain = LINK.matcher(plain).replaceAll("$1");
        plain = LINK_DEFINITION.matcher(plain).replaceAll("");
        plain = AUTOLINK.matcher(plain).replaceAll("");
        return BARE_URL.matcher(plain).replaceAll("").strip();
    }

    /** The provider's result as an answer, or empty when it gave none and the question is refunded. */
    static Optional<Answer> answer(LlmJsonResult result, List<ScoredArticle> retrieved) {
        return switch (result) {
            case LlmJsonResult.Blocked blocked -> Optional.of(new Answer("blocked", REFUSAL, List.of()));
            case LlmJsonResult.Failed failed -> Optional.empty();
            case LlmJsonResult.Answered answered -> parse(answered.json(), retrieved);
        };
    }

    private static Optional<Answer> parse(String json, List<ScoredArticle> retrieved) {
        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (JacksonException e) {
            return Optional.empty();
        }
        String text = root.path("answer").isString() ? withoutLinks(root.path("answer").asString()) : "";
        if (!root.path("answered").asBoolean(false) || text.isEmpty()) {
            return Optional.of(new Answer("no_answer", NO_ANSWER, List.of()));
        }
        Map<String, ScoredArticle> bySlug = retrieved.stream()
                .collect(Collectors.toMap(ScoredArticle::slug, Function.identity(), (first, second) -> first));
        Set<String> cited = new LinkedHashSet<>();
        JsonNode slugs = root.path("citedSlugs");
        for (int i = 0; i < slugs.size(); i++) {
            if (slugs.get(i).isString() && bySlug.containsKey(slugs.get(i).asString())) {
                cited.add(slugs.get(i).asString());
            }
        }
        return Optional.of(new Answer("answered", text, cited.stream().map(bySlug::get).toList()));
    }

    private AssistantReply reply(Asker asker, UUID conversationId, String question, Instant askedAt, Answer answer,
            QuotaView quota, long started) {
        List<String> slugs = answer.sources().stream().map(ScoredArticle::slug).toList();
        UUID id = conversations.append(asker, conversationId,
                new StoredMessage("user", question, null, List.of(), askedAt),
                new StoredMessage("assistant", answer.text(), answer.outcome(), slugs, clock.instant()));
        telemetry.record(answer.outcome(), slugs.size(), length(question), started);
        List<Source> sources = answer.sources().stream().map(a -> new Source(a.slug(), a.title())).toList();
        return new AssistantReply(id, new ReplyMessage("assistant", answer.text(), answer.outcome(), sources), quota);
    }

    private ConversationView view(StoredConversation conversation) {
        Set<String> slugs = new HashSet<>();
        conversation.messages().forEach(message -> slugs.addAll(message.citedSlugs()));
        Map<String, String> titles = conversations.publishedTitles(slugs);
        List<HistoryMessage> messages = conversation.messages().stream()
                .map(message -> new HistoryMessage(message.role(), message.text(), message.outcome(),
                        message.citedSlugs().stream().filter(titles::containsKey)
                                .map(slug -> new Source(slug, titles.get(slug))).toList(),
                        message.createdAt()))
                .toList();
        return new ConversationView(conversation.id(), messages);
    }
}
