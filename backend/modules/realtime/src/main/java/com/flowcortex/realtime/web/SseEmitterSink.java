package com.flowcortex.realtime.web;

import com.flowcortex.realtime.application.SseFrame;
import com.flowcortex.realtime.application.StreamSink;
import java.io.IOException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter.SseEventBuilder;

/**
 * Adapts an {@link SseEmitter} to the hub's sink. A timeout completes the stream normally: left
 * to Spring MVC it would raise an async-timeout error against a response already committed as
 * {@code text/event-stream}.
 */
final class SseEmitterSink implements StreamSink {

    private final SseEmitter emitter;

    SseEmitterSink(SseEmitter emitter) {
        this.emitter = emitter;
        emitter.onTimeout(emitter::complete);
    }

    @Override
    public void send(SseFrame frame) throws IOException {
        SseEventBuilder builder = SseEmitter.event();
        if (frame.comment() != null) {
            builder.comment(frame.comment());
        } else {
            if (frame.id() != null) {
                builder.id(frame.id());
            }
            builder.name(frame.event()).data(frame.data());
        }
        emitter.send(builder);
    }

    @Override
    public void complete() {
        emitter.complete();
    }

    @Override
    public void onClose(Runnable callback) {
        emitter.onCompletion(callback);
        emitter.onError(failure -> callback.run());
    }
}
