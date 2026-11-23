package com.flowcortex.content.web;

import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.user.api.UserDirectory;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Resolves the JWT subject into the live user row, so a suspended account is refused with 403 and a token that
 * outlived its row with 404, as every staff write is attributed to a row that exists.
 */
@Component
class ContentCaller {

    private final UserDirectory userDirectory;

    ContentCaller(UserDirectory userDirectory) {
        this.userDirectory = userDirectory;
    }

    UUID require(Jwt jwt) {
        UUID id = UUID.fromString(jwt.getSubject());
        return userDirectory.findById(id)
                .orElseThrow(() -> new NotFoundException(new LocalizedMessage("user.current.notFound", id)))
                .id();
    }
}
