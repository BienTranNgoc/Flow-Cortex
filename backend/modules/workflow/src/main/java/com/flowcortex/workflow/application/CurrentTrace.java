package com.flowcortex.workflow.application;

import com.flowcortex.workflow.domain.RunTraceIds;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;

/** The trace id a run is stored with when it starts. */
final class CurrentTrace {

    private CurrentTrace() {
    }

    /** The active span's trace id, or a random one when tracing is off or the id is not usable. */
    static String idFor(Tracer tracer) {
        Span span = tracer.currentSpan();
        return RunTraceIds.resolve(span == null ? null : span.context().traceId(), RunTraceIds::random);
    }
}
