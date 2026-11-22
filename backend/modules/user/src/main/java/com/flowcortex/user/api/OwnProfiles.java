package com.flowcortex.user.api;

import java.util.UUID;

/**
 * Self-service profile reads and writes for an already-authenticated caller. Every method throws
 * {@link com.flowcortex.shared.web.NotFoundException} (404) for an unknown id and {@link
 * org.springframework.security.access.AccessDeniedException} (403) for a suspended account.
 */
public interface OwnProfiles {

    OwnProfile get(UUID userId);

    /** Names are trimmed; a blank first or last name clears it. */
    OwnProfile updateNames(UUID userId, String name, String firstName, String lastName);

    /**
     * Changes the username under the shared policy. Throws {@code UsernameChangeTooSoonException}
     * (422, inside the cooldown), {@link com.flowcortex.shared.web.UnprocessableEntityException} (422,
     * {@code USERNAME_NOT_ALLOWED}) and {@code RegistrationConflictException} (409, taken or held).
     */
    OwnProfile changeUsername(UUID userId, String username);
}
