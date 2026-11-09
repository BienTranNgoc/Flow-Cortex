package com.flowcortex.app.chat;

import com.flowcortex.chat.config.ChatProperties;
import com.flowcortex.chat.config.ChatSwitch;
import com.flowcortex.chat.turn.ChatEvents;
import com.flowcortex.llm.api.LlmChatClient;
import com.flowcortex.llm.api.LlmChatListener;
import com.flowcortex.llm.api.LlmChatOutcome;
import com.flowcortex.llm.api.LlmChatRequest;
import com.flowcortex.llm.api.LlmJsonResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A scriptable provider, a recording event seam that still forwards to the real realtime binding, and an
 * environment switch a test flips at runtime: every chat IT shares one application context, so none of them
 * may need a property of its own.
 */
@TestConfiguration(proxyBeanMethods = false)
class ChatTestConfig {

    @Bean
    @Primary
    StubChatClient stubChatClient() {
        return new StubChatClient();
    }

    @Bean
    @Primary
    RecordingEvents recordingEvents(@Qualifier("chatRealtimeEvents") ChatEvents realtime) {
        return new RecordingEvents(realtime);
    }

    @Bean
    @Primary
    SwitchableChatSwitch switchableChatSwitch(ChatProperties properties, JdbcTemplate jdbcTemplate) {
        return new SwitchableChatSwitch(properties, jdbcTemplate);
    }

    /** The environment half of the chat switch, on by default; the console half stays in the database. */
    static final class SwitchableChatSwitch extends ChatSwitch {

        private volatile boolean environmentEnabled = true;

        SwitchableChatSwitch(ChatProperties properties, JdbcTemplate jdbcTemplate) {
            super(properties, jdbcTemplate);
        }

        void environmentEnabled(boolean enabled) {
            environmentEnabled = enabled;
        }

        @Override
        public boolean envEnabled() {
            return environmentEnabled;
        }
    }

    interface Step {

        /** Runs against the listener; a non-null outcome ends the round there. */
        LlmChatOutcome run(LlmChatListener listener) throws InterruptedException;
    }

    record Script(List<Step> steps, LlmChatOutcome outcome) {
    }

    static final class StubChatClient implements LlmChatClient {

        private final Queue<Script> scripts = new ConcurrentLinkedQueue<>();
        final List<LlmChatRequest> requests = new CopyOnWriteArrayList<>();

        void reset() {
            scripts.clear();
            requests.clear();
        }

        /** The next round streams the steps in order, then ends with {@code outcome}. */
        void script(LlmChatOutcome outcome, Step... steps) {
            scripts.add(new Script(List.of(steps), outcome));
        }

        static LlmChatOutcome finished() {
            return new LlmChatOutcome.Finished("stub-model", 12, 7, List.of());
        }

        static LlmChatOutcome failed() {
            return new LlmChatOutcome.Failed(LlmJsonResult.Kind.UPSTREAM);
        }

        static Step text(String chunk) {
            return listener -> {
                listener.onText(chunk);
                return listener.cancelled() ? new LlmChatOutcome.Cancelled() : null;
            };
        }

        static Step signal(CountDownLatch latch) {
            return listener -> {
                latch.countDown();
                return null;
            };
        }

        /** Blocks until the gate opens, ending as cancelled the moment the listener says so. */
        static Step hold(CountDownLatch gate) {
            return listener -> {
                while (!gate.await(20, TimeUnit.MILLISECONDS)) {
                    if (listener.cancelled()) {
                        return new LlmChatOutcome.Cancelled();
                    }
                }
                return null;
            };
        }

        @Override
        public LlmChatOutcome stream(LlmChatRequest request, LlmChatListener listener) {
            requests.add(request);
            Script script = scripts.poll();
            if (script == null) {
                listener.onText("ok");
                return finished();
            }
            try {
                for (Step step : script.steps()) {
                    LlmChatOutcome ended = step.run(listener);
                    if (ended != null) {
                        return ended;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new LlmChatOutcome.Cancelled();
            }
            return script.outcome();
        }
    }

    record Delta(UUID conversationId, UUID messageId, int offset, String text) {
    }

    record Update(UUID conversationId, UUID messageId) {
    }

    static final class RecordingEvents implements ChatEvents {

        private final ChatEvents delegate;
        private final List<Delta> deltas = new CopyOnWriteArrayList<>();
        private final List<Update> updates = new CopyOnWriteArrayList<>();

        RecordingEvents(ChatEvents delegate) {
            this.delegate = delegate;
        }

        void reset() {
            deltas.clear();
            updates.clear();
        }

        List<Delta> deltasOf(UUID messageId) {
            return new ArrayList<>(deltas.stream().filter(d -> d.messageId().equals(messageId)).toList());
        }

        long updatesOf(UUID messageId) {
            return updates.stream().filter(u -> u.messageId().equals(messageId)).count();
        }

        @Override
        public void delta(UUID conversationId, UUID messageId, int offset, String text) {
            deltas.add(new Delta(conversationId, messageId, offset, text));
            delegate.delta(conversationId, messageId, offset, text);
        }

        @Override
        public void updated(UUID conversationId, UUID messageId) {
            updates.add(new Update(conversationId, messageId));
            delegate.updated(conversationId, messageId);
        }
    }
}
