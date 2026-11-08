package com.flowcortex.space.api;

import java.util.UUID;

/** The space an authenticated account works in; it comes from the account id, never from client input. */
public interface SessionSpaces {

    /**
     * Returns the account's personal space, creating it when missing.
     *
     * @throws com.flowcortex.shared.web.LocalizedStatusException 503 {@code feature.turnedOff} when spaces are switched off
     */
    SpaceContext sessionSpace(UUID userId);
}
