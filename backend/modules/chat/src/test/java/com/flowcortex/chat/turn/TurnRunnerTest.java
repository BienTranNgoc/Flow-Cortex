package com.flowcortex.chat.turn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowcortex.chat.config.ChatProperties;
import com.flowcortex.chat.quota.ChatQuota;
import com.flowcortex.chat.tools.ChatTools;
import com.flowcortex.chat.tools.ToolResult;
import com.flowcortex.chat.turn.TurnStore.Turn;
import com.flowcortex.llm.api.LlmChatClient;
import com.flowcortex.llm.api.LlmChatOutcome;
import com.flowcortex.llm.api.LlmJsonResult;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class TurnRunnerTest {

    private static final Instant NOW = Instant.parse("2026-11-08T05:00:00Z");
    private static final LocalDate DAY = LocalDate.of(2026, 11, 8);
    private static final int WAIT_MS = 5000;

    private final TurnStore store = mock(TurnStore.class);
    private final ChatEvents events = mock(ChatEvents.class);
    private final LlmChatClient llm = mock(LlmChatClient.class);
    private final ChatQuota quota = mock(ChatQuota.class);
    private final ChatTelemetry telemetry = mock(ChatTelemetry.class);
    private final ChatTools tools = mock(ChatTools.class);
    private TurnRunner runner;

    private static LlmChatOutcome finished() {
        return new LlmChatOutcome.Finished("m", 5, 3, List.of());
    }

    private TurnRunner runner(int threads, int queue) {
        ChatProperties properties = new ChatProperties(true, "model", new ChatProperties.Quota(100, 5000, 10),
                Duration.ofSeconds(90), Duration.ofSeconds(30), 4, threads, queue);
        runner = new TurnRunner(store, events, llm, quota, telemetry, properties, Clock.fixed(NOW, ZoneOffset.UTC),
                tools, JsonMapper.builder().build());
        return runner;
    }

    private TurnJob job(Instant deadline) {
        return new TurnJob(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, "en", DAY, deadline);
    }

    private void alive() {
        when(store.stopRequested(any())).thenReturn(Optional.of(false));
        when(store.window(any(), anyInt(), anyInt())).thenReturn(
                List.of(new Turn("user", "text", "done", "hi", null, null, null, null)));
    }

    @AfterEach
    void stop() {
        runner.shutdown();
    }

    @Test
    void aFinishedAnswerIsStreamedStoredAsDoneAndAnnounced() {
        alive();
        when(llm.stream(any(), any())).thenAnswer(call -> {
            call.<com.flowcortex.llm.api.LlmChatListener>getArgument(1).onText("Hello ");
            call.<com.flowcortex.llm.api.LlmChatListener>getArgument(1).onText("[a](https://x.example) world");
            return finished();
        });
        when(store.finish(any(), eq("done"), any(), any())).thenReturn(1);
        TurnJob job = job(NOW.plusSeconds(60));

        runner(1, 1).start(job);

        verify(store, timeout(WAIT_MS)).finish(job.assistantId(), "done", "Hello a world", null);
        verify(events, timeout(WAIT_MS)).updated(job.conversationId(), job.assistantId());
        verify(events).delta(job.conversationId(), job.assistantId(), 0, "Hello ");
        verify(events).delta(job.conversationId(), job.assistantId(), 6, "[a](https://x.example) world");
        verify(quota, never()).refund(any(), any());
    }

    @Test
    void aBlankAnswerFailsAsEmptyAndRefundsTheCharge() {
        alive();
        when(llm.stream(any(), any())).thenReturn(finished());
        when(store.finish(any(), eq("failed"), any(), any())).thenReturn(1);
        TurnJob job = job(NOW.plusSeconds(60));

        runner(1, 1).start(job);

        verify(store, timeout(WAIT_MS)).finish(job.assistantId(), "failed", "", "{\"error\":\"empty\"}");
        verify(quota, timeout(WAIT_MS)).refund(job.ownerId(), DAY);
    }

    @Test
    void aProviderFailureAndABlockedPromptEachFailTheTurnWithTheirOwnCode() {
        alive();
        when(store.finish(any(), eq("failed"), any(), any())).thenReturn(1);
        when(llm.stream(any(), any())).thenReturn(new LlmChatOutcome.Failed(LlmJsonResult.Kind.UPSTREAM),
                new LlmChatOutcome.Blocked("safety"));
        TurnJob first = job(NOW.plusSeconds(60));
        TurnJob second = job(NOW.plusSeconds(60));

        TurnRunner started = runner(1, 2);
        started.start(first);
        started.start(second);

        verify(store, timeout(WAIT_MS)).finish(first.assistantId(), "failed", "", "{\"error\":\"upstream\"}");
        verify(store, timeout(WAIT_MS)).finish(second.assistantId(), "failed", "", "{\"error\":\"blocked\"}");
    }

    @Test
    void aStopRequestedMidAnswerEndsItAsStoppedKeepingTheTextSoFar() {
        when(store.window(any(), anyInt(), anyInt())).thenReturn(
                List.of(new Turn("user", "text", "done", "hi", null, null, null, null)));
        when(store.stopRequested(any())).thenReturn(Optional.of(false), Optional.of(true));
        when(llm.stream(any(), any())).thenAnswer(call -> {
            var listener = call.<com.flowcortex.llm.api.LlmChatListener>getArgument(1);
            listener.onText("par");
            return listener.cancelled() ? new LlmChatOutcome.Cancelled() : finished();
        });
        when(store.finish(any(), eq("stopped"), any(), any())).thenReturn(1);
        TurnJob job = job(NOW.plusSeconds(60));

        runner(1, 1).start(job);

        verify(store, timeout(WAIT_MS)).finish(job.assistantId(), "stopped", "par", null);
        verify(quota, never()).refund(any(), any());
    }

    @Test
    void aTurnPastItsDeadlineFailsAsTimeout() {
        alive();
        when(llm.stream(any(), any())).thenAnswer(call -> {
            var listener = call.<com.flowcortex.llm.api.LlmChatListener>getArgument(1);
            listener.onText("slow");
            return listener.cancelled() ? new LlmChatOutcome.Cancelled() : finished();
        });
        when(store.finish(any(), eq("failed"), any(), any())).thenReturn(1);
        TurnJob job = job(NOW.minusSeconds(1));

        runner(1, 1).start(job);

        verify(store, timeout(WAIT_MS)).finish(job.assistantId(), "failed", "slow", "{\"error\":\"timeout\"}");
    }

    @Test
    void aRowDeletedBeforeTheTurnStartsEndsQuietly() {
        when(store.stopRequested(any())).thenReturn(Optional.empty());
        TurnJob job = job(NOW.plusSeconds(60));

        runner(1, 1).start(job);

        verify(telemetry, timeout(WAIT_MS)).turn(eq("gone"), eq("before_start"), anyInt(), anyInt(), anyLong());
        verify(store, never()).finish(any(), anyString(), any(), any());
        verify(events, never()).updated(any(), any());
    }

    @Test
    void aRowDeletedWhileCheckpointingEndsQuietlyWithoutTerminalWrite() {
        alive();
        when(store.checkpoint(any(), any())).thenReturn(0);
        when(llm.stream(any(), any())).thenAnswer(call -> {
            var listener = call.<com.flowcortex.llm.api.LlmChatListener>getArgument(1);
            listener.onText("x".repeat(TurnRunner.CHECKPOINT_CHARS));
            return listener.cancelled() ? new LlmChatOutcome.Cancelled() : finished();
        });
        TurnJob job = job(NOW.plusSeconds(60));

        runner(1, 1).start(job);

        verify(telemetry, timeout(WAIT_MS)).turn(eq("gone"), eq("row_ended"), anyInt(), anyInt(), anyLong());
        verify(store, never()).finish(any(), anyString(), any(), any());
    }

    @Test
    void aLostTerminalWriteNeitherRefundsNorAnnounces() {
        alive();
        when(llm.stream(any(), any())).thenReturn(new LlmChatOutcome.Failed(LlmJsonResult.Kind.UPSTREAM));
        when(store.finish(any(), any(), any(), any())).thenReturn(0);
        TurnJob job = job(NOW.plusSeconds(60));

        runner(1, 1).start(job);

        verify(telemetry, timeout(WAIT_MS)).turn(eq("superseded"), eq("failed"), anyInt(), anyInt(), anyLong());
        verify(quota, never()).refund(any(), any());
        verify(events, never()).updated(any(), any());
    }

    @Test
    void anUnexpectedErrorFailsTheTurnAsInternal() {
        alive();
        when(llm.stream(any(), any())).thenThrow(new IllegalStateException("boom"));
        when(store.finish(any(), eq("failed"), any(), any())).thenReturn(1);
        TurnJob job = job(NOW.plusSeconds(60));

        runner(1, 1).start(job);

        verify(store, timeout(WAIT_MS)).finish(job.assistantId(), "failed", "", "{\"error\":\"internal\"}");
    }

    @Test
    void aFailingEventPublishNeverStopsTheTurn() {
        alive();
        org.mockito.Mockito.doThrow(new IllegalStateException("bus")).when(events).delta(any(), any(), anyInt(), any());
        when(llm.stream(any(), any())).thenAnswer(call -> {
            call.<com.flowcortex.llm.api.LlmChatListener>getArgument(1).onText("ok");
            return finished();
        });
        when(store.finish(any(), eq("done"), any(), any())).thenReturn(1);
        TurnJob job = job(NOW.plusSeconds(60));

        runner(1, 1).start(job);

        verify(store, timeout(WAIT_MS)).finish(job.assistantId(), "done", "ok", null);
    }

    @Test
    void textAfterAToolRoundStartsANewParagraph() {
        alive();
        when(tools.call(any(), eq("find_workflows"), any())).thenReturn(new ToolResult("{}", false));
        when(llm.stream(any(), any())).thenAnswer(call -> {
            call.<com.flowcortex.llm.api.LlmChatListener>getArgument(1).onText("One");
            return new LlmChatOutcome.Finished("m", 1, 1,
                    List.of(new LlmChatOutcome.ToolCall("find_workflows", "{}", null)));
        }).thenAnswer(call -> {
            call.<com.flowcortex.llm.api.LlmChatListener>getArgument(1).onText("Two");
            return finished();
        });
        when(store.finish(any(), eq("done"), any(), any())).thenReturn(1);
        TurnJob job = job(NOW.plusSeconds(60));

        runner(1, 1).start(job);

        verify(store, timeout(WAIT_MS)).finish(job.assistantId(), "done", "One\n\nTwo", null);
        verify(events, timeout(WAIT_MS)).delta(job.conversationId(), job.assistantId(), 3, "\n\nTwo");
    }

    @Test
    void aFullPoolFailsTheExtraTurnAtOnceAsBusyAndReportsNotIdleWhileWorking() throws Exception {
        alive();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch running = new CountDownLatch(1);
        when(llm.stream(any(), any())).thenAnswer(call -> {
            running.countDown();
            release.await(WAIT_MS, TimeUnit.MILLISECONDS);
            return new LlmChatOutcome.Cancelled();
        });
        when(store.finish(any(), any(), any(), any())).thenReturn(1);
        TurnRunner started = runner(1, 1);
        TurnJob busy = job(NOW.plusSeconds(60));
        TurnJob queued = job(NOW.plusSeconds(60));
        TurnJob rejected = job(NOW.plusSeconds(60));

        started.start(busy);
        assertThat(running.await(WAIT_MS, TimeUnit.MILLISECONDS)).isTrue();
        started.start(queued);
        started.start(rejected);

        assertThat(started.idle()).isFalse();
        verify(store, timeout(WAIT_MS)).finish(rejected.assistantId(), "failed", "", "{\"error\":\"busy\"}");
        verify(quota, timeout(WAIT_MS)).refund(rejected.ownerId(), DAY);
        release.countDown();
    }
}
