package com.flowcortex.chat.turn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowcortex.chat.config.ChatProperties;
import com.flowcortex.chat.config.ChatSwitch;
import com.flowcortex.chat.conversation.ConversationStore;
import com.flowcortex.chat.conversation.ConversationStore.StoredConversation;
import com.flowcortex.chat.conversation.ConversationStore.StoredMessage;
import com.flowcortex.chat.quota.ChatQuota;
import com.flowcortex.chat.turn.TurnStore.AnswerRow;
import com.flowcortex.chat.turn.TurnStore.Claim;
import com.flowcortex.chat.web.ChatProblemException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

class TurnServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-08T05:00:00Z");
    private static final LocalDate DAY = LocalDate.of(2026, 11, 8);

    private final TurnStore store = mock(TurnStore.class);
    private final ConversationStore conversations = mock(ConversationStore.class);
    private final TurnRunner runner = mock(TurnRunner.class);
    private final ChatQuota quota = mock(ChatQuota.class);
    private final ChatSwitch chatSwitch = mock(ChatSwitch.class);
    private final ChatTelemetry telemetry = mock(ChatTelemetry.class);
    private final TurnService service = new TurnService(store, conversations, runner, quota, chatSwitch, telemetry,
            new ChatProperties(true, "model", new ChatProperties.Quota(100, 5000, 10), Duration.ofSeconds(90),
                    Duration.ofSeconds(30), 4, 8, 32),
            mock(MessageSource.class), JsonMapper.builder().build(), Clock.fixed(NOW, ZoneOffset.UTC),
            mock(PlatformTransactionManager.class));

    private final UUID owner = UUID.randomUUID();
    private final UUID conversation = UUID.randomUUID();

    private static StoredMessage message(int seq, String role) {
        return new StoredMessage(UUID.randomUUID(), seq, role, "text", "b", null, "done", NOW);
    }

    private void sendable() {
        when(chatSwitch.available()).thenReturn(true);
        when(store.claimKey(any(), any(), any(), any(), any(), any())).thenReturn(true);
        when(store.allocateSeq(eq(owner), any(), anyInt())).thenReturn(Optional.of(1));
        when(store.message(any())).thenReturn(Optional.of(message(1, "user")));
        when(quota.charge(owner)).thenReturn(DAY);
    }

    private static ChatProblemException refusal(Runnable call) {
        try {
            call.run();
        } catch (ChatProblemException e) {
            return e;
        }
        throw new AssertionError("expected a refusal");
    }

    @Test
    void aSendStoresBothRowsChargesOnceAndHandsTheTurnToTheRunnerAfterTheTransaction() {
        sendable();

        TurnService.Started started = service.send(owner, conversation, "k1", "Làm video", null, "vi");

        ArgumentCaptor<TurnJob> job = ArgumentCaptor.forClass(TurnJob.class);
        verify(runner).start(job.capture());
        assertThat(job.getValue().assistantId()).isEqualTo(started.assistantMessageId());
        assertThat(job.getValue().chargeDay()).isEqualTo(DAY);
        assertThat(job.getValue().language()).isEqualTo("vi");
        assertThat(job.getValue().deadline()).isEqualTo(NOW.plusSeconds(90));
        verify(store).insertAssistant(eq(started.assistantMessageId()), eq(conversation), eq(2), eq(NOW.plusSeconds(90)),
                any());
        verify(store).setChargeDay(started.assistantMessageId(), DAY);
        verify(store).retitleDefault(eq(conversation), eq("Làm video"), any(), any());
    }

    @Test
    void aSecondMessageKeepsTheConversationTitle() {
        sendable();
        when(store.hasUserMessage(conversation)).thenReturn(true);

        service.send(owner, conversation, "k1", "again", null, "en");

        verify(store, never()).retitleDefault(any(), any(), any(), any());
    }

    @Test
    void aDisabledChatRefusesBeforeAnythingIsStored() {
        when(chatSwitch.available()).thenReturn(false);

        ChatProblemException refused = refusal(() -> service.send(owner, conversation, "k", "x", null, "en"));

        assertThat(refused.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        verify(telemetry).refused("chat-disabled");
        verify(store, never()).claimKey(any(), any(), any(), any(), any(), any());
        verify(runner, never()).start(any());
    }

    @Test
    void aRepeatedKeyWithTheSameRequestReplaysTheStoredResultWithoutAnotherTurn() {
        sendable();
        TurnService.Started first = service.send(owner, conversation, "k1", "same", null, "en");
        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        verify(store).claimKey(any(), any(), hash.capture(), any(), any(), any());
        when(store.claimKey(any(), any(), any(), any(), any(), any())).thenReturn(false);
        StoredMessage answer = message(2, "assistant");
        when(store.claim(owner, "k1")).thenReturn(Optional.of(new Claim(hash.getValue(), conversation, answer.id())));
        when(store.message(answer.id())).thenReturn(Optional.of(answer));
        when(store.userMessageBefore(conversation, 2)).thenReturn(Optional.of(message(1, "user")));

        TurnService.Started replay = service.send(owner, conversation, "k1", "same", null, "en");

        assertThat(replay.assistantMessageId()).isEqualTo(answer.id());
        assertThat(first.assistantMessageId()).isNotEqualTo(replay.assistantMessageId());
        verify(runner, times(1)).start(any());
    }

    @Test
    void aKeyReusedForADifferentRequestIsRefusedWithNoCharge() {
        sendable();
        when(store.claimKey(any(), any(), any(), any(), any(), any())).thenReturn(false);
        when(store.claim(owner, "k1")).thenReturn(Optional.of(new Claim("other", conversation, UUID.randomUUID())));

        ChatProblemException refused = refusal(() -> service.send(owner, conversation, "k1", "x", null, "en"));

        assertThat(refused.code()).isEqualTo("idempotency-key-reused");
        verify(quota, never()).charge(any());
        verify(telemetry).refused("idempotency-key-reused");
    }

    @Test
    void aRefusedChargeStoresNothingAndRefundsNothing() {
        sendable();
        when(quota.charge(owner)).thenThrow(ChatProblemException.quotaExhausted(NOW));

        ChatProblemException refused = refusal(() -> service.send(owner, conversation, "k", "x", null, "en"));

        assertThat(refused.code()).isEqualTo("chat-quota");
        verify(quota, never()).refund(any(), any());
        verify(runner, never()).start(any());
    }

    @Test
    void aFailureAfterTheChargeGivesTheChargeBack() {
        sendable();
        org.mockito.Mockito.doThrow(new IllegalStateException("db")).when(store).setChargeDay(any(), any());

        assertThatThrownBy(() -> service.send(owner, conversation, "k", "x", null, "en"))
                .isInstanceOf(IllegalStateException.class);

        verify(quota).refund(owner, DAY);
        verify(runner, never()).start(any());
    }

    @Test
    void aMissingConversationRefusesTheSendAsNotFound() {
        sendable();
        when(store.allocateSeq(eq(owner), any(), anyInt())).thenReturn(Optional.empty());

        assertThat(refusal(() -> service.send(owner, conversation, "k", "x", null, "en")).status())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void creatingAConversationNamesItAfterTheMessageWhenNoTitleIsGiven() {
        sendable();
        StoredConversation stored = new StoredConversation(conversation, "t", NOW, NOW);
        when(conversations.find(eq(owner), any())).thenReturn(Optional.of(stored));

        TurnService.CreatedConversation created = service.create(owner, null, "k", "  Hello\nworld  ", null, "en");

        assertThat(created.conversation()).isEqualTo(stored);
        verify(conversations).create(eq(owner), any(UUID.class), eq("Hello world"));
        verify(runner).start(any());
    }

    @Test
    void creatingWithATitleKeepsItAndAVanishedConversationIsNotFound() {
        sendable();
        when(conversations.find(eq(owner), any())).thenReturn(Optional.empty());

        assertThat(refusal(() -> service.create(owner, "Mine", "k", "x", null, "en")).status())
                .isEqualTo(HttpStatus.NOT_FOUND);
        verify(conversations).create(eq(owner), any(UUID.class), eq("Mine"));
    }

    @Test
    void aFailedAnswerIsRetriedAsANewChargedTurn() {
        sendable();
        UUID answerId = UUID.randomUUID();
        when(store.conversationOwned(owner, conversation)).thenReturn(true);
        when(store.answerRow(conversation, answerId)).thenReturn(Optional.of(new AnswerRow(2, "assistant", "text", "failed")));
        when(store.userMessageBefore(conversation, 2)).thenReturn(Optional.of(message(1, "user")));

        TurnService.Started started = service.retry(owner, conversation, answerId, "k", "en");

        ArgumentCaptor<TurnJob> job = ArgumentCaptor.forClass(TurnJob.class);
        verify(runner).start(job.capture());
        assertThat(job.getValue().assistantId()).isEqualTo(started.assistantMessageId());
        assertThat(job.getValue().userSeq()).isEqualTo(1);
        verify(store).allocateSeq(owner, conversation, 1);
    }

    @Test
    void aRetryOfADoneAnswerACardOrAnAnswerWithNoQuestionIsRefused() {
        sendable();
        UUID answerId = UUID.randomUUID();
        when(store.conversationOwned(owner, conversation)).thenReturn(true);
        when(store.userMessageBefore(conversation, 2)).thenReturn(Optional.of(message(1, "user")));

        when(store.answerRow(conversation, answerId)).thenReturn(Optional.of(new AnswerRow(2, "assistant", "text", "done")));
        assertThat(refusal(() -> service.retry(owner, conversation, answerId, "k", "en")).code())
                .isEqualTo("chat-turn-not-retryable");

        when(store.answerRow(conversation, answerId)).thenReturn(Optional.of(new AnswerRow(2, "assistant", "card", "failed")));
        assertThat(refusal(() -> service.retry(owner, conversation, answerId, "k", "en")).code())
                .isEqualTo("chat-turn-not-retryable");

        when(store.userMessageBefore(conversation, 2)).thenReturn(Optional.empty());
        assertThat(refusal(() -> service.retry(owner, conversation, answerId, "k", "en")).code())
                .isEqualTo("chat-turn-not-retryable");
        verify(runner, never()).start(any());
    }

    @Test
    void aRetryOfAnAnswerThatLaterMessagesSupersededIsRefusedWithoutCharging() {
        sendable();
        UUID answerId = UUID.randomUUID();
        when(store.conversationOwned(owner, conversation)).thenReturn(true);
        when(store.answerRow(conversation, answerId)).thenReturn(Optional.of(new AnswerRow(2, "assistant", "text", "failed")));
        when(store.userMessageBefore(conversation, 2)).thenReturn(Optional.of(message(1, "user")));
        when(store.hasMessageAfter(conversation, 2)).thenReturn(true);

        assertThat(refusal(() -> service.retry(owner, conversation, answerId, "k", "en")).code())
                .isEqualTo("chat-turn-not-retryable");
        verify(store, never()).allocateSeq(any(), any(), anyInt());
        verify(runner, never()).start(any());
    }

    @Test
    void aRetryOfAForeignConversationOrAMissingAnswerIsNotFound() {
        sendable();
        UUID answerId = UUID.randomUUID();

        assertThat(refusal(() -> service.retry(owner, conversation, answerId, "k", "en")).code())
                .isEqualTo("chat-conversation-not-found");

        when(store.conversationOwned(owner, conversation)).thenReturn(true);
        when(store.answerRow(conversation, answerId)).thenReturn(Optional.empty());
        assertThat(refusal(() -> service.retry(owner, conversation, answerId, "k", "en")).code())
                .isEqualTo("chat-message-not-found");
    }

    @Test
    void stopAsksARunningTurnToStopAndToleratesOneThatAlreadyEnded() {
        UUID answerId = UUID.randomUUID();
        when(store.conversationOwned(owner, conversation)).thenReturn(true);
        when(store.requestStop(conversation, answerId)).thenReturn(1);

        service.stop(owner, conversation, answerId);

        when(store.requestStop(conversation, answerId)).thenReturn(0);
        when(store.answerRow(conversation, answerId)).thenReturn(Optional.of(new AnswerRow(2, "assistant", "text", "done")));
        service.stop(owner, conversation, answerId);

        when(store.answerRow(conversation, answerId)).thenReturn(Optional.empty());
        assertThat(refusal(() -> service.stop(owner, conversation, answerId)).code())
                .isEqualTo("chat-message-not-found");
    }

    @Test
    void stopOnAForeignConversationIsNotFound() {
        assertThat(refusal(() -> service.stop(owner, conversation, UUID.randomUUID())).code())
                .isEqualTo("chat-conversation-not-found");
    }

    @Test
    void aTitleIsOneLineOfAtMostSixtyCodePoints() {
        assertThat(TurnService.titleOf("  a\nb\r\nc ")).isEqualTo("a b c");
        assertThat(TurnService.titleOf("x".repeat(100))).hasSize(TurnService.TITLE_CHARS);
        assertThat(TurnService.titleOf("😀".repeat(100)).codePointCount(0,
                TurnService.titleOf("😀".repeat(100)).length())).isEqualTo(TurnService.TITLE_CHARS);
        assertThat(List.of(TurnService.titleOf("short"))).containsExactly("short");
    }
}
