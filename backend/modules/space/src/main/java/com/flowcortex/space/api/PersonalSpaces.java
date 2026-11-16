package com.flowcortex.space.api;

import java.util.UUID;

/** The personal space of an owner, for writers that stamp data with it; it never reads the spaces switch. */
public interface PersonalSpaces {

    /** Returns the owner's personal space id, creating the space and its manager membership when missing. */
    UUID personalSpaceOf(UUID ownerId);
}
