package com.flowcortex.workflow.web;

import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.user.api.CurrentUser;
import com.flowcortex.user.api.UserDirectory;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Resolves the JWT subject into a live user id via {@code user :: api} — the concrete reason
 * {@code :modules:workflow} depends on it. Defensive: a token can outlive the row it was minted
 * for (same caveat as {@code AuthService#updateOwnProfile}).
 */
@Component
class CurrentUserResolver {

    private final UserDirectory userDirectory;

    CurrentUserResolver(UserDirectory userDirectory) {
        this.userDirectory = userDirectory;
    }

    UUID require(Jwt jwt) {
        return current(jwt).id();
    }

    private CurrentUser current(Jwt jwt) {
        UUID id = UUID.fromString(jwt.getSubject());
        return userDirectory.findById(id).orElseThrow(() -> new NotFoundException(new LocalizedMessage("user.current.notFound", id)));
    }
}
