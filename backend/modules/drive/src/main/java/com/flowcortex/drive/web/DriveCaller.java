package com.flowcortex.drive.web;

import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.space.api.SessionSpaces;
import com.flowcortex.user.api.UserDirectory;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Resolves who a Drive request acts for: the authenticated account and its personal space, never
 * anything the client sent.
 */
@Component
public class DriveCaller {

    private final UserDirectory userDirectory;
    private final SessionSpaces sessionSpaces;

    DriveCaller(UserDirectory userDirectory, SessionSpaces sessionSpaces) {
        this.userDirectory = userDirectory;
        this.sessionSpaces = sessionSpaces;
    }

    /**
     * Returns the caller behind the token.
     *
     * @throws NotFoundException 404 {@code user.current.notFound} when the account no longer exists
     * @throws com.flowcortex.shared.web.LocalizedStatusException 503 {@code feature.turnedOff} when spaces are off
     */
    public Caller require(Jwt jwt) {
        UUID id = UUID.fromString(jwt.getSubject());
        UUID userId = userDirectory.findById(id)
                .orElseThrow(() -> new NotFoundException(new LocalizedMessage("user.current.notFound", id)))
                .id();
        return new Caller(userId, sessionSpaces.sessionSpace(userId).spaceId());
    }

    /** The account and the personal space every Drive query and write is scoped to. */
    public record Caller(UUID userId, UUID spaceId) {
    }
}
