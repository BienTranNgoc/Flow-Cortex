package com.flowcortex.chat.turn;

import com.flowcortex.chat.cards.Attachments;
import com.flowcortex.chat.config.ChatProperties;
import com.flowcortex.chat.quota.ChatQuota;
import com.flowcortex.chat.tools.ChatTools;
import com.flowcortex.chat.tools.ToolContext;
import com.flowcortex.chat.tools.ToolRounds;
import com.flowcortex.llm.api.AnswerText;
import com.flowcortex.llm.api.LlmChatClient;
import com.flowcortex.llm.api.LlmChatListener;
import com.flowcortex.llm.api.LlmChatOutcome;
import com.flowcortex.llm.api.LlmChatRequest;
import io.micrometer.context.ContextSnapshotFactory;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs turns on a bounded pool ({@code turn-threads}, a queue of {@code turn-queue}). The answer streams to
 * {@link ChatEvents} and is checkpointed to its row; the one terminal write is conditional on the row still
 * streaming, and only the writer that wins it refunds a failure and announces the end. A turn whose row is
 * gone (conversation deleted) or already ended by the sweeper just stops, quietly.
 */
@Component("chatTurnRunner")
public class TurnRunner {

    static final int CHECKPOINT_CHARS = 400;
    static final long CHECKPOINT_NANOS = TimeUnit.SECONDS.toNanos(1);
    static final long POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(500);

    private static final Logger LOG = LoggerFactory.getLogger(TurnRunner.class);

    private final TurnStore store;
    private final ChatEvents events;
    private final LlmChatClient llm;
    private final ChatQuota quota;
    private final ChatTelemetry telemetry;
    private final ChatProperties properties;
    private final Clock clock;
    private final ChatTools tools;
    private final JsonMapper jsonMapper;
    private final ThreadPoolExecutor executor;
    private final ContextSnapshotFactory snapshots = ContextSnapshotFactory.builder().build();

    TurnRunner(TurnStore store, ChatEvents events, LlmChatClient llm, ChatQuota quota, ChatTelemetry telemetry,
            ChatProperties properties, Clock clock, ChatTools tools, JsonMapper jsonMapper) {
        this.tools = tools;
        this.jsonMapper = jsonMapper;
        this.store = store;
        this.events = events;
        this.llm = llm;
        this.quota = quota;
        this.telemetry = telemetry;
        this.properties = properties;
        this.clock = clock;
        AtomicInteger threadNumber = new AtomicInteger();
        this.executor = new ThreadPoolExecutor(properties.turnThreads(), properties.turnThreads(), 0L,
                TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(properties.turnQueue()), task -> {
                    Thread thread = new Thread(task, "chat-turn-" + threadNumber.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                });
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    /** True when no turn is running or waiting on this instance. */
    public boolean idle() {
        return executor.getActiveCount() == 0 && executor.getQueue().isEmpty();
    }

    /** Queues the turn; a full pool fails it at once, so the conversation is never left locked. */
    public void start(TurnJob job) {
        Runnable task = snapshots.captureAll().wrap(() -> run(job));
        try {
            executor.execute(task);
        } catch (RejectedExecutionException e) {
            LOG.warn("chat.turn outcome=rejected threads={} queue={}", properties.turnThreads(),
                    properties.turnQueue());
            conclude(job, "failed", "", "busy", System.nanoTime(), 0, 0);
        }
    }

    private void run(TurnJob job) {
        long started = System.nanoTime();
        TurnListener listener = new TurnListener(job);
        try {
            if (store.stopRequested(job.assistantId()).isEmpty()) {
                telemetry.turn("gone", "before_start", 0, 0, started);
                return;
            }
            LlmChatRequest request = ChatPromptBuilder.request(properties.model(),
                    store.window(job.conversationId(), job.userSeq(), ChatPromptBuilder.WINDOW), job.language(),
                    tools.declarations());
            ToolContext context = new ToolContext(job.ownerId(), job.conversationId(), Attachments.fromPayload(
                    jsonMapper, store.payloadAt(job.conversationId(), job.userSeq())));
            ToolRounds.Result result = ToolRounds.run(llm, request, tools, context, listener,
                    properties.maxToolRounds(), listener::startNewRound);
            if (result.limitReached()) {
                LOG.warn("chat.turn outcome=tool_rounds_exhausted rounds={}", properties.maxToolRounds());
            }
            end(job, listener, result.outcome(), result.cardProposed(), started);
        } catch (RuntimeException e) {
            LOG.error("chat.turn outcome=error", e);
            conclude(job, "failed", listener.sanitized(), "internal", started, 0, 0);
        }
    }

    private void end(TurnJob job, TurnListener listener, LlmChatOutcome outcome, boolean cardProposed,
            long started) {
        String body = listener.sanitized();
        switch (outcome) {
            case LlmChatOutcome.Finished finished -> {
                if (body.isBlank() && !cardProposed) {
                    conclude(job, "failed", body, "empty", started, finished.inputTokens(), finished.outputTokens());
                } else {
                    conclude(job, "done", body, null, started, finished.inputTokens(), finished.outputTokens());
                }
            }
            case LlmChatOutcome.Cancelled _ -> {
                switch (listener.endedBy()) {
                    case GONE -> telemetry.turn("gone", "row_ended", 0, 0, started);
                    case TIMEOUT -> conclude(job, "failed", body, "timeout", started, 0, 0);
                    default -> conclude(job, "stopped", body, null, started, 0, 0);
                }
            }
            case LlmChatOutcome.Blocked _ -> conclude(job, "failed", body, "blocked", started, 0, 0);
            case LlmChatOutcome.Failed failed ->
                    conclude(job, "failed", body, failed.kind().name().toLowerCase(Locale.ROOT), started, 0, 0);
        }
    }

    /** The conditional terminal write, then (for the winner only) the refund of a failure and the event. */
    private void conclude(TurnJob job, String status, String body, String errorCode, long started, int inputTokens,
            int outputTokens) {
        String payload = errorCode == null ? null : "{\"error\":\"" + errorCode + "\"}";
        if (store.finish(job.assistantId(), status, body, payload) != 1) {
            telemetry.turn("superseded", status, 0, 0, started);
            return;
        }
        if ("failed".equals(status)) {
            refund(job);
        }
        events.updated(job.conversationId(), job.assistantId());
        telemetry.turn(status, errorCode == null ? "none" : errorCode, inputTokens, outputTokens, started);
    }

    private void refund(TurnJob job) {
        if (job.chargeDay() == null) {
            return;
        }
        try {
            quota.refund(job.ownerId(), job.chargeDay());
        } catch (RuntimeException e) {
            LOG.error("chat.turn outcome=refund_failed", e);
        }
    }

    enum EndedBy { NONE, GONE, STOP, TIMEOUT }

    /**
     * One streamed round. {@link #cancelled()} is polled from a watcher thread as well as the calling one:
     * it reads the row at most every 500 ms and caches the verdict, and the first reason found is kept.
     */
    private final class TurnListener implements LlmChatListener {

        private final TurnJob job;
        private final StringBuilder buffer = new StringBuilder();
        private final AtomicReference<EndedBy> ended = new AtomicReference<>(EndedBy.NONE);
        private final AtomicLong lastPoll = new AtomicLong(System.nanoTime() - POLL_NANOS);
        private volatile boolean pendingBreak;
        private int checkpointedLength;
        private long lastCheckpoint = System.nanoTime();

        TurnListener(TurnJob job) {
            this.job = job;
        }

        /** The next piece of text starts a new paragraph: it follows a round of tool calls. */
        void startNewRound() {
            pendingBreak = true;
        }

        @Override
        public void onText(String chunk) {
            if (pendingBreak && !buffer.isEmpty()) {
                chunk = "\n\n" + chunk;
            }
            pendingBreak = false;
            int offset = buffer.length();
            buffer.append(chunk);
            try {
                events.delta(job.conversationId(), job.assistantId(), offset, chunk);
            } catch (RuntimeException e) {
                LOG.warn("chat.delta outcome=publish_failed", e);
            }
            checkpoint();
        }

        @Override
        public void onToolCall(String name, String argsJson, String providerState) {
            // The calls arrive again on the round's Finished outcome, which is where they run.
        }

        @Override
        public boolean cancelled() {
            if (ended.get() != EndedBy.NONE) {
                return true;
            }
            long now = System.nanoTime();
            long last = lastPoll.get();
            if (now - last >= POLL_NANOS && lastPoll.compareAndSet(last, now)) {
                poll();
            }
            if (ended.get() == EndedBy.NONE && clock.instant().isAfter(job.deadline())) {
                ended.compareAndSet(EndedBy.NONE, EndedBy.TIMEOUT);
            }
            return ended.get() != EndedBy.NONE;
        }

        EndedBy endedBy() {
            EndedBy reason = ended.get();
            return reason == EndedBy.NONE ? EndedBy.STOP : reason;
        }

        String sanitized() {
            return AnswerText.withoutLinks(buffer.toString());
        }

        private void poll() {
            try {
                Optional<Boolean> stop = store.stopRequested(job.assistantId());
                if (stop.isEmpty()) {
                    ended.compareAndSet(EndedBy.NONE, EndedBy.GONE);
                } else if (stop.get()) {
                    ended.compareAndSet(EndedBy.NONE, EndedBy.STOP);
                }
            } catch (RuntimeException e) {
                LOG.warn("chat.turn outcome=poll_failed", e);
            }
        }

        private void checkpoint() {
            int unsaved = buffer.length() - checkpointedLength;
            long now = System.nanoTime();
            if (unsaved < CHECKPOINT_CHARS && now - lastCheckpoint < CHECKPOINT_NANOS) {
                return;
            }
            lastCheckpoint = now;
            checkpointedLength = buffer.length();
            try {
                if (store.checkpoint(job.assistantId(), sanitized()) == 0) {
                    ended.compareAndSet(EndedBy.NONE, EndedBy.GONE);
                }
            } catch (RuntimeException e) {
                LOG.warn("chat.turn outcome=checkpoint_failed", e);
            }
        }
    }
}
