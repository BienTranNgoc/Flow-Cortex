package com.flowcortex.asset.web;

import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.space.api.SessionSpaces;
import com.flowcortex.space.api.SpaceAccess;
import com.flowcortex.space.api.SpaceAction;
import com.flowcortex.user.api.UserDirectory;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Resolves whose library a request reads: the authenticated account and its session space, never
 * anything the client sent, after checking the account's membership grants reading it.
 */
@Component
class AssetCaller {

    private final UserDirectory userDirectory;
    private final SessionSpaces sessionSpaces;
    private final SpaceAccess spaceAccess;

    AssetCaller(UserDirectory userDirectory, SessionSpaces sessionSpaces, SpaceAccess spaceAccess) {
        this.userDirectory = userDirectory;
        this.sessionSpaces = sessionSpaces;
        this.spaceAccess = spaceAccess;
    }

    /**
     * Returns the caller behind the token.
     *
     * @throws NotFoundException 404 {@code user.current.notFound} when the account no longer exists, and
     *         404 {@code asset.notFound} when its membership does not grant reading the space
     * @throws com.flowcortex.shared.web.LocalizedStatusException 503 {@code feature.turnedOff} when spaces are off
     */
    Caller require(Jwt jwt) {
        UUID id = UUID.fromString(jwt.getSubject());
        UUID userId = userDirectory.findById(id)
                .orElseThrow(() -> new NotFoundException(new LocalizedMessage("user.current.notFound", id)))
                .id();
        UUID spaceId = sessionSpaces.sessionSpace(userId).spaceId();
        spaceAccess.assertCan(userId, SpaceAction.SPACE_READ, spaceId,
                () -> new NotFoundException(new LocalizedMessage("asset.notFound")));
        return new Caller(userId, spaceId);
    }

    /** The account and the space every library read is scoped to. */
    record Caller(UUID userId, UUID spaceId) {
    }
}
