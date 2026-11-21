package com.flowcortex.chat.turn;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One turn to run: the answer row to fill, the member message it answers ({@code userSeq}), the language
 * resolved at send time and the day its quota charge was booked on.
 */
public record TurnJob(UUID conversationId, UUID ownerId, UUID assistantId, int userSeq, String language,
        LocalDate chargeDay, Instant deadline) {
}
