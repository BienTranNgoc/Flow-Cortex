package com.flowcortex.entity.web;

import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.space.api.SessionSpaces;
import com.flowcortex.space.api.SpaceAccess;
import com.flowcortex.space.api.SpaceAction;
import com.flowcortex.space.api.SpaceContext;
import com.flowcortex.user.api.UserDirectory;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Resolves who an entity request acts for: the authenticated account and its personal space, never anything the
 * client sent, and refuses an account that may not take the action in that space.
 */
@Component
public class EntityCaller {

    private final UserDirectory userDirectory;
    private final SessionSpaces sessionSpaces;
    private final SpaceAccess spaceAccess;

    EntityCaller(UserDirectory userDirectory, SessionSpaces sessionSpaces, SpaceAccess spaceAccess) {
        this.userDirectory = userDirectory;
        this.sessionSpaces = sessionSpaces;
        this.spaceAccess = spaceAccess;
    }

    /**
     * Returns the caller behind the token.
     *
     * @throws NotFoundException 404 {@code user.current.notFound} when the account no longer exists, or
     *         {@code entity.notFound} when the account may not take {@code action} in its space
     * @throws com.flowcortex.shared.web.LocalizedStatusException 503 {@code feature.turnedOff} when spaces are off
     */
    public Caller require(Jwt jwt, SpaceAction action) {
        UUID id = UUID.fromString(jwt.getSubject());
        UUID userId = userDirectory.findById(id)
                .orElseThrow(() -> new NotFoundException(new LocalizedMessage("user.current.notFound", id)))
                .id();
        SpaceContext space = sessionSpaces.sessionSpace(userId);
        spaceAccess.assertCan(userId, action, space.spaceId(),
                () -> new NotFoundException(new LocalizedMessage("entity.notFound")));
        return new Caller(userId, space.spaceId());
    }

    /** The account and the personal space every entity query and write is scoped to. */
    public record Caller(UUID userId, UUID spaceId) {
    }
}
