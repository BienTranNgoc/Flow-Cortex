package com.flowcortex.chat.turn;

import java.util.UUID;

/**
 * Where a running turn announces itself to the open thread. Calls come from turn threads and must not block
 * or throw for a delivery problem.
 */
public interface ChatEvents {

    /** {@code text} is the next piece of the answer; {@code offset} is the number of characters before it. */
    void delta(UUID conversationId, UUID messageId, int offset, String text);

    /** The answer reached its final state; the thread reloads the message. */
    void updated(UUID conversationId, UUID messageId);
}
