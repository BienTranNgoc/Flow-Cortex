package com.flowcortex.support.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.flowcortex.llm.api.LlmJsonResult;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.support.assistant.AssistantService.AssistantReply;
import com.flowcortex.support.assistant.AssistantService.ConversationView;
import com.flowcortex.support.assistant.AssistantService.Source;
import com.flowcortex.support.assistant.ConversationStore.StoredConversation;
import com.flowcortex.support.assistant.ConversationStore.StoredMessage;
import com.flowcortex.support.config.AssistantSwitch;
import com.flowcortex.support.config.SupportProperties;
import com.flowcortex.support.kb.KnowledgeBaseService.ScoredArticle;
import com.flowcortex.support.web.SupportProblemException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;

class AssistantServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-19T08:20:00Z");
    private static final Asker MEMBER = new Asker.Member(UUID.randomUUID());
    private static final Asker.Guest GUEST = new Asker.Guest("session-hash", "network-hash");
    private static final String QUESTION = "Làm sao nạp credit?";
    private static final ScoredArticle NAP = new ScoredArticle("nap-credit", "Nạp credit", "Vào Hồ sơ", "Credit", 4);
    private static final ScoredArticle GOI = new ScoredArticle("goi-credit", "Gói credit", "Mua gói", "Credit", 2);

    private final AssistantSwitch assistantSwitch = mock(AssistantSwitch.class);
    private final ConversationStore conversations = mock(ConversationStore.class);
    private final QuotaService quotas = mock(QuotaService.class);
    private final MinuteLimiter minuteLimiter = mock(MinuteLimiter.class);
    private final Retriever retriever = mock(Retriever.class);
    private final LlmDeadline llm = mock(LlmDeadline.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final UUID conversationId = UUID.randomUUID();
    private final QuotaService.Charge charge =
            new QuotaService.Charge(LocalDate.of(2026, 10, 19), List.of("global", MEMBER.subjectKey()), 49);
    private AssistantService service;

    @BeforeEach
    void setUp() {
        SupportProperties properties = new SupportProperties(true, "", new SupportProperties.Assistant(true,
                "gemini-test", 2000, 50, 10, 100), new SupportProperties.Mail("no-reply@flow-cortex.dev"));
        service = new AssistantService(assistantSwitch, properties, conversations, quotas, minuteLimiter, retriever,
                llm, new AssistantTelemetry(meters, ObservationRegistry.NOOP), Clock.fixed(NOW, ZoneOffset.UTC));
        when(assistantSwitch.available()).thenReturn(true);
        when(retriever.retrieve(anyString())).thenReturn(List.of(NAP, GOI));
        when(quotas.charge(eq(MEMBER), anyInt())).thenReturn(charge);
        when(conversations.append(eq(MEMBER), any(), any(), any())).thenReturn(conversationId);
    }

    @Test
    void anAnswerKeepsOnlyCitedArticlesThatWereRetrieved() {
        answer("{\"answered\":true,\"answer\":\"Vào Hồ sơ.\",\"citedSlugs\":[\"goi-credit\",\"xoa\",\"goi-credit\"]}");

        AssistantReply reply = service.ask(MEMBER, null, "  " + QUESTION + " ", Locale.ENGLISH);

        assertThat(reply.conversationId()).isEqualTo(conversationId);
        assertThat(reply.message().outcome()).isEqualTo("answered");
        assertThat(reply.message().text()).isEqualTo("Vào Hồ sơ.");
        assertThat(reply.message().sources()).containsExactly(new Source("goi-credit", "Gói credit"));
        assertThat(reply.quota().remaining()).isEqualTo(49);
        assertThat(reply.quota().resetsAt()).isEqualTo(Instant.parse("2026-10-19T17:00:00Z"));
        ArgumentCaptor<StoredMessage> question = ArgumentCaptor.forClass(StoredMessage.class);
        ArgumentCaptor<StoredMessage> answer = ArgumentCaptor.forClass(StoredMessage.class);
        verify(conversations).append(eq(MEMBER), eq(null), question.capture(), answer.capture());
        assertThat(question.getValue().text()).isEqualTo(QUESTION);
        assertThat(answer.getValue().citedSlugs()).containsExactly("goi-credit");
        assertThat(meters.counter(AssistantTelemetry.OUTCOME_COUNTER, "outcome", "answered").count()).isEqualTo(1);
    }

    @Test
    void theProviderSayingTheArticlesDoNotAnswerIsANoAnswer() {
        answer("{\"answered\":false,\"answer\":\"\",\"citedSlugs\":[\"nap-credit\"]}");

        AssistantReply reply = service.ask(MEMBER, null, QUESTION, Locale.ENGLISH);

        assertThat(reply.message().outcome()).isEqualTo("no_answer");
        assertThat(reply.message().text()).isEqualTo(AssistantService.NO_ANSWER);
        assertThat(reply.message().sources()).isEmpty();
        verify(quotas, never()).refund(any());
    }

    @Test
    void aBlockedAnswerIsTheRefusalAndStaysCounted() {
        when(llm.call(any())).thenReturn(new LlmJsonResult.Blocked("SAFETY"));

        AssistantReply reply = service.ask(MEMBER, null, QUESTION, Locale.ENGLISH);

        assertThat(reply.message().outcome()).isEqualTo("blocked");
        assertThat(reply.message().text()).isEqualTo(AssistantService.REFUSAL);
        verify(quotas, never()).refund(any());
    }

    @Test
    void aFailedOrUnreadableAnswerIsRefundedAndUnavailable() {
        when(llm.call(any())).thenReturn(new LlmJsonResult.Failed(LlmJsonResult.Kind.UNREACHABLE),
                new LlmJsonResult.Answered("not json", "m", 1, 1));

        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> service.ask(MEMBER, null, QUESTION, Locale.ENGLISH))
                    .isInstanceOfSatisfying(SupportProblemException.class,
                            e -> assertThat(e.code()).isEqualTo("assistant-unavailable"));
        }

        verify(quotas, times(2)).refund(charge);
        verify(conversations, never()).append(any(), any(), any(), any());
        assertThat(meters.counter(AssistantTelemetry.OUTCOME_COUNTER, "outcome", "unavailable").count())
                .isEqualTo(2);
    }

    @Test
    void noRetrievedArticleAnswersWithoutChargingOrCallingTheProvider() {
        when(retriever.retrieve(anyString())).thenReturn(List.of());
        when(quotas.peek(MEMBER)).thenReturn(new QuotaService.Remaining(50, Instant.parse("2026-10-19T17:00:00Z")));

        AssistantReply reply = service.ask(MEMBER, null, QUESTION, Locale.ENGLISH);

        assertThat(reply.message().outcome()).isEqualTo("no_answer");
        assertThat(reply.quota().remaining()).isEqualTo(50);
        verify(quotas, never()).charge(any(), anyInt());
        verifyNoInteractions(llm);
    }

    @Test
    void theAssistantSwitchedOffRefusesBeforeAnythingElse() {
        when(assistantSwitch.available()).thenReturn(false);

        assertThatThrownBy(() -> service.ask(MEMBER, null, QUESTION, Locale.ENGLISH))
                .isInstanceOfSatisfying(SupportProblemException.class,
                        e -> assertThat(e.code()).isEqualTo("assistant-disabled"));
        verifyNoInteractions(retriever, quotas, llm, minuteLimiter);
    }

    @Test
    void anEmptyOrTooLongQuestionIsRefused() {
        assertThatThrownBy(() -> service.ask(MEMBER, null, "   ", Locale.ENGLISH))
                .isInstanceOfSatisfying(SupportProblemException.class,
                        e -> assertThat(e.code()).isEqualTo("validation-failed"));
        assertThatThrownBy(() -> service.ask(MEMBER, null, null, Locale.ENGLISH))
                .isInstanceOfSatisfying(SupportProblemException.class,
                        e -> assertThat(e.code()).isEqualTo("validation-failed"));
        assertThatThrownBy(() -> service.ask(MEMBER, null, "a".repeat(1001), Locale.ENGLISH))
                .isInstanceOfSatisfying(SupportProblemException.class,
                        e -> assertThat(e.code()).isEqualTo("question-too-long"));
        verifyNoInteractions(retriever, llm);
    }

    @Test
    void aConversationTheAskerDoesNotOwnIsNotFound() {
        UUID foreign = UUID.randomUUID();
        when(conversations.owns(MEMBER, foreign)).thenReturn(false);

        assertThatThrownBy(() -> service.ask(MEMBER, foreign, QUESTION, Locale.ENGLISH))
                .isInstanceOfSatisfying(SupportProblemException.class,
                        e -> assertThat(e.code()).isEqualTo("conversation-not-found"));
        verifyNoInteractions(retriever, llm);
    }

    @Test
    void aRefusedChargeIsCountedByItsKind() {
        when(quotas.charge(eq(MEMBER), anyInt())).thenThrow(QuotaService.exhausted(NOW));

        assertThatThrownBy(() -> service.ask(MEMBER, null, QUESTION, Locale.ENGLISH)).isInstanceOf(SupportProblemException.class);

        assertThat(meters.counter(AssistantTelemetry.OUTCOME_COUNTER, "outcome", "quota").count()).isEqualTo(1);
        verifyNoInteractions(llm);
    }

    @Test
    void anAnswerIsStoredAndReturnedWithoutLinksOrImages() {
        answer("{\"answered\":true,\"answer\":\"Xem [Nạp credit](/help/nap-credit), [mục khác]( mailto:a@evil.example) "
                + "và ![logo](https://cdn.x/l.png).\\n\\n[ref]: https://evil.example/r\","
                + "\"citedSlugs\":[\"nap-credit\"]}");

        AssistantReply reply = service.ask(MEMBER, null, QUESTION, Locale.ENGLISH);

        assertThat(reply.message().text())
                .isEqualTo("Xem Nạp credit, mục khác và logo.")
                .doesNotContain("](", "evil", "mailto", "cdn.x");
        ArgumentCaptor<StoredMessage> stored = ArgumentCaptor.forClass(StoredMessage.class);
        verify(conversations).append(eq(MEMBER), eq(null), any(), stored.capture());
        assertThat(stored.getValue().text()).isEqualTo(reply.message().text());
    }

    @Test
    void anAnswerThatWasOnlyAnImageIsANoAnswer() {
        answer("{\"answered\":true,\"answer\":\"![](https://evil.example/only.png)\",\"citedSlugs\":[\"nap-credit\"]}");

        AssistantReply reply = service.ask(MEMBER, null, QUESTION, Locale.ENGLISH);

        assertThat(reply.message().outcome()).isEqualTo("no_answer");
        assertThat(reply.message().text()).isEqualTo(AssistantService.NO_ANSWER);
    }

    @Test
    void aGuestQuestionIsHeldToTheNetworkMinuteCapBeforeAnyRetrievalOrWrite() {
        doThrow(QuotaService.exhausted(NOW)).when(minuteLimiter).acquireNetwork(GUEST.networkKey());

        assertThatThrownBy(() -> service.ask(GUEST, null, QUESTION, Locale.ENGLISH))
                .isInstanceOfSatisfying(SupportProblemException.class,
                        e -> assertThat(e.code()).isEqualTo("assistant-quota"));

        verifyNoInteractions(retriever, llm, conversations);
        verify(minuteLimiter, never()).acquire(anyString());
        assertThat(meters.counter(AssistantTelemetry.OUTCOME_COUNTER, "outcome", "quota").count()).isEqualTo(1);
    }

    @Test
    void aMemberIsNotHeldToANetworkCap() {
        answer("{\"answered\":true,\"answer\":\"Vào Hồ sơ.\",\"citedSlugs\":[\"nap-credit\"]}");

        service.ask(MEMBER, null, QUESTION, Locale.ENGLISH);

        verify(minuteLimiter, never()).acquireNetwork(anyString());
    }

    @Test
    void aGuestQuestionNoArticleMatchesStillCountsAgainstTheGuestQuotas() {
        when(retriever.retrieve(anyString())).thenReturn(List.of());
        QuotaService.Charge guestCharge = new QuotaService.Charge(LocalDate.of(2026, 10, 19),
                List.of(GUEST.subjectKey(), GUEST.networkKey()), 9);
        when(quotas.chargeAskerOnly(GUEST)).thenReturn(guestCharge);
        when(conversations.append(eq(GUEST), any(), any(), any())).thenReturn(conversationId);

        AssistantReply reply = service.ask(GUEST, null, QUESTION, Locale.ENGLISH);

        assertThat(reply.message().outcome()).isEqualTo("no_answer");
        assertThat(reply.quota().remaining()).isEqualTo(9);
        verify(quotas).chargeAskerOnly(GUEST);
        verify(quotas, never()).charge(any(), anyInt());
        verifyNoInteractions(llm);
    }

    @Test
    void aGuestOutOfQuotaIsRefusedEvenWhenNoArticleMatches() {
        when(retriever.retrieve(anyString())).thenReturn(List.of());
        when(quotas.chargeAskerOnly(GUEST)).thenThrow(QuotaService.exhausted(NOW));

        assertThatThrownBy(() -> service.ask(GUEST, null, QUESTION, Locale.ENGLISH))
                .isInstanceOfSatisfying(SupportProblemException.class,
                        e -> assertThat(e.code()).isEqualTo("assistant-quota"));

        verify(conversations, never()).append(any(), any(), any(), any());
    }

    @Test
    void aFailureWritingAChargedGuestNoAnswerRefundsIt() {
        when(retriever.retrieve(anyString())).thenReturn(List.of());
        QuotaService.Charge guestCharge = new QuotaService.Charge(LocalDate.of(2026, 10, 19),
                List.of(GUEST.subjectKey(), GUEST.networkKey()), 9);
        when(quotas.chargeAskerOnly(GUEST)).thenReturn(guestCharge);
        when(conversations.append(eq(GUEST), any(), any(), any()))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        assertThatThrownBy(() -> service.ask(GUEST, null, QUESTION, Locale.ENGLISH))
                .isInstanceOf(DataAccessResourceFailureException.class);

        verify(quotas).refund(guestCharge);
    }

    @Test
    void rotatingSessionIdsFromOneAddressIsRefusedAtTheEleventhQuestionInAMinute() {
        MinuteLimiter real = new MinuteLimiter(Clock.fixed(NOW, ZoneOffset.UTC));
        SupportProperties properties = new SupportProperties(true, "", new SupportProperties.Assistant(true,
                "gemini-test", 2000, 50, 10, 100), new SupportProperties.Mail("no-reply@flow-cortex.dev"));
        AssistantService limited = new AssistantService(assistantSwitch, properties, conversations, quotas, real,
                retriever, llm, new AssistantTelemetry(meters, ObservationRegistry.NOOP), Clock.fixed(NOW, ZoneOffset.UTC));
        GuestKeys keys = new GuestKeys(properties, "support-hash-secret-for-unit-tests-0123456789");
        when(retriever.retrieve(anyString())).thenReturn(List.of());
        when(quotas.chargeAskerOnly(any())).thenReturn(new QuotaService.Charge(LocalDate.of(2026, 10, 19), List.of(), 9));
        when(conversations.append(any(), any(), any(), any())).thenReturn(conversationId);

        for (int i = 0; i < MinuteLimiter.PER_MINUTE; i++) {
            limited.ask(keys.guest(UUID.randomUUID().toString(), "198.51.100.7"), null, QUESTION, Locale.ENGLISH);
        }

        assertThatThrownBy(() -> limited.ask(keys.guest(UUID.randomUUID().toString(), "198.51.100.7"), null, QUESTION,
                Locale.ENGLISH))
                .isInstanceOfSatisfying(SupportProblemException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(e.code()).isEqualTo("assistant-quota");
                });
        verify(retriever, times(MinuteLimiter.PER_MINUTE)).retrieve(anyString());
    }

    @Test
    void theChargeUsesTheCeilingTheQuestionWasCheckedAgainst() {
        when(quotas.refuseWhenPaused()).thenReturn(1234);
        answer("{\"answered\":true,\"answer\":\"Vào Hồ sơ.\",\"citedSlugs\":[\"goi-credit\"]}");

        service.ask(MEMBER, null, QUESTION, Locale.ENGLISH);

        verify(quotas).charge(MEMBER, 1234);
    }

    @Test
    void aPausedAssistantRefusesEvenAQuestionNoArticleMatches() {
        when(retriever.retrieve(anyString())).thenReturn(List.of());
        doThrow(new SupportProblemException(HttpStatus.SERVICE_UNAVAILABLE, "assistant-paused",
                new LocalizedMessage("support.assistant.paused")))
                .when(quotas).refuseWhenPaused();

        assertThatThrownBy(() -> service.ask(MEMBER, null, QUESTION, Locale.ENGLISH))
                .isInstanceOfSatisfying(SupportProblemException.class,
                        e -> assertThat(e.code()).isEqualTo("assistant-paused"));
        verifyNoInteractions(retriever, llm, conversations);
        assertThat(meters.counter(AssistantTelemetry.OUTCOME_COUNTER, "outcome", "paused").count()).isEqualTo(1);
    }

    @Test
    void aFailureAfterTheChargeIsRefundedAndRethrown() {
        answer("{\"answered\":true,\"answer\":\"Vào Hồ sơ.\",\"citedSlugs\":[\"nap-credit\"]}");
        when(conversations.append(eq(MEMBER), any(), any(), any()))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        assertThatThrownBy(() -> service.ask(MEMBER, null, QUESTION, Locale.ENGLISH))
                .isInstanceOf(DataAccessResourceFailureException.class);

        verify(quotas).refund(charge);
    }

    @Test
    void aQuestionIsMeasuredInCharactersNotUtf16Units() {
        answer("{\"answered\":true,\"answer\":\"Vào Hồ sơ.\",\"citedSlugs\":[\"nap-credit\"]}");
        String emoji = "😀";

        assertThat(service.ask(MEMBER, null, emoji.repeat(1000), Locale.ENGLISH).message().outcome()).isEqualTo("answered");
        assertThatThrownBy(() -> service.ask(MEMBER, null, emoji.repeat(1001), Locale.ENGLISH))
                .isInstanceOfSatisfying(SupportProblemException.class,
                        e -> assertThat(e.code()).isEqualTo("question-too-long"));
    }

    @Test
    void theLatestConversationLinksOnlyArticlesStillPublished() {
        when(conversations.latest(MEMBER)).thenReturn(Optional.of(new StoredConversation(conversationId, List.of(
                new StoredMessage("user", QUESTION, null, List.of(), NOW),
                new StoredMessage("assistant", "Vào Hồ sơ.", "answered", List.of("nap-credit", "cu"), NOW)))));
        when(conversations.publishedTitles(any())).thenReturn(Map.of("nap-credit", "Nạp credit"));

        ConversationView view = service.latest(MEMBER).orElseThrow();

        assertThat(view.conversationId()).isEqualTo(conversationId);
        assertThat(view.messages()).hasSize(2);
        assertThat(view.messages().get(1).sources()).containsExactly(new Source("nap-credit", "Nạp credit"));
    }

    private void answer(String json) {
        when(llm.call(any())).thenReturn(new LlmJsonResult.Answered(json, "gemini-test", 100, 20));
    }
}
