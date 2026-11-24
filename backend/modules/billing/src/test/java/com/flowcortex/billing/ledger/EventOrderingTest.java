package com.flowcortex.billing.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class EventOrderingTest {

    private static final Instant RECORDED = Instant.parse("2026-11-20T10:00:05Z");

    @Test
    void anEventWithNoTimestampIsInOrderWhenNothingIsRecorded() {
        assertThat(EventOrdering.refusal(null, null)).isEmpty();
    }

    @Test
    void anEventWithATimestampIsInOrderWhenNothingIsRecorded() {
        assertThat(EventOrdering.refusal(Instant.parse("2026-11-20T10:00:01Z"), null)).isEmpty();
    }

    @Test
    void anEventWithNoTimestampIsUnorderedOnceOneIsRecorded() {
        assertThat(EventOrdering.refusal(null, RECORDED)).contains("unordered_event");
    }

    @Test
    void anEventStatingAnEarlierTimestampIsStale() {
        assertThat(EventOrdering.refusal(Instant.parse("2026-11-20T10:00:01Z"), RECORDED)).contains("stale_event");
    }

    @Test
    void anEventOneNanosecondEarlierIsStale() {
        assertThat(EventOrdering.refusal(RECORDED.minusNanos(1), RECORDED)).contains("stale_event");
    }

    @Test
    void anEventStatingTheRecordedTimestampIsInOrder() {
        assertThat(EventOrdering.refusal(RECORDED, RECORDED)).isEmpty();
    }

    @Test
    void anEventStatingALaterTimestampIsInOrder() {
        assertThat(EventOrdering.refusal(Instant.parse("2026-11-20T10:00:09Z"), RECORDED)).isEmpty();
    }
}
