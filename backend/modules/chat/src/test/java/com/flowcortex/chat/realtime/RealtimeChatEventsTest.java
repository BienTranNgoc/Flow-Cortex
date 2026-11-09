package com.flowcortex.chat.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.flowcortex.realtime.api.RealtimeEvents;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RealtimeChatEventsTest {

    private record Published(String topic, String type, Object payload) {
    }

    private final List<Published> published = new ArrayList<>();
    private final RealtimeEvents recorder = (topic, type, payload) -> published.add(
            new Published(topic, type, payload));
    private final UUID conversation = UUID.randomUUID();
    private final UUID message = UUID.randomUUID();

    @Test
    void aDeltaIsPublishedOnTheConversationTopicWithItsOffset() {
        new RealtimeChatEvents(recorder).delta(conversation, message, 12, "xin chào");

        assertThat(published).containsExactly(new Published("chat:" + conversation, "chat.delta",
                Map.of("messageId", message, "offset", 12, "text", "xin chào")));
    }

    @Test
    void aLongDeltaIsPublishedAsSeveralEventsInOrder() {
        String text = "a".repeat(DeltaSplitter.MAX_BYTES + 5);

        new RealtimeChatEvents(recorder).delta(conversation, message, 0, text);

        assertThat(published).extracting(Published::payload).containsExactly(
                Map.of("messageId", message, "offset", 0, "text", "a".repeat(DeltaSplitter.MAX_BYTES)),
                Map.of("messageId", message, "offset", DeltaSplitter.MAX_BYTES, "text", "a".repeat(5)));
    }

    @Test
    void anUpdatedSignalNamesTheMessage() {
        new RealtimeChatEvents(recorder).updated(conversation, message);

        assertThat(published).containsExactly(new Published("chat:" + conversation, "chat.updated",
                Map.of("messageId", message)));
    }

    @Test
    void aFailingBusNeverReachesTheTurn() {
        RealtimeChatEvents events = new RealtimeChatEvents((topic, type, payload) -> {
            throw new IllegalStateException("broker down");
        });

        assertThatCode(() -> events.delta(conversation, message, 0, "x")).doesNotThrowAnyException();
        assertThatCode(() -> events.updated(conversation, message)).doesNotThrowAnyException();
    }
}
