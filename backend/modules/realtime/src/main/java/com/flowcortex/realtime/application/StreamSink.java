package com.flowcortex.realtime.application;

import java.io.IOException;

/** Where the hub writes a stream's frames; the web layer adapts an {@code SseEmitter} to it. */
public interface StreamSink {

    /** Writes one frame; an {@link IOException} means the client is gone. */
    void send(SseFrame frame) throws IOException;

    /** Ends the stream normally. */
    void complete();

    /** Runs {@code callback} when the transport ends the stream (completion, error, timeout, disconnect). */
    void onClose(Runnable callback);
}
