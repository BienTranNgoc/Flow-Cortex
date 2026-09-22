package com.flowcortex.workflow.web;

import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.user.api.UserDirectory;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Resolves the JWT subject into a live user id via the {@code user :: api}
 * port (design.md D2 — this is the concrete reason {@code :modules:workflow}
 * depends on {@code user :: api}: it needs the current caller's identity,
 * not just the module boundary declared unused). Defensive: a token can
 * outlive the row it was minted for ({@code AuthService#updateOwnProfile}
 * carries the same caveat in {@code :modules:user}).
 */
@Component
class CurrentUserResolver {

    private final UserDirectory userDirectory;

    CurrentUserResolver(UserDirectory userDirectory) {
        this.userDirectory = userDirectory;
    }

    UUID require(Jwt jwt) {
        UUID id = UUID.fromString(jwt.getSubject());
        userDirectory.findById(id).orElseThrow(() -> new NotFoundException("Current user not found: " + id));
        return id;
    }
}
