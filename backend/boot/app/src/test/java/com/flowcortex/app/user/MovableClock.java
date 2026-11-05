package com.flowcortex.app.user;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/** System time plus an offset a test can move; every reader of the app's Clock sees it. */
class MovableClock extends Clock {

    private final AtomicReference<Duration> offset = new AtomicReference<>(Duration.ZERO);

    void advance(Duration by) {
        offset.updateAndGet(current -> current.plus(by));
    }

    void reset() {
        offset.set(Duration.ZERO);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        throw new UnsupportedOperationException("fixed to UTC");
    }

    @Override
    public Instant instant() {
        return Instant.now().plus(offset.get());
    }
}
