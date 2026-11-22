package com.flowcortex.trash.web;

import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.LocalizedStatusException;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.trash.application.TrashProperties;
import com.flowcortex.user.api.UserDirectory;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * The gate of every Trash route: 503 while the Trash is switched off, then the JWT subject resolved into a live
 * account. {@link UserDirectory#findById} refuses a suspended account with 403, and a token can outlive its row (404).
 */
@Component
class TrashCaller {

    private final TrashProperties properties;
    private final UserDirectory userDirectory;

    TrashCaller(TrashProperties properties, UserDirectory userDirectory) {
        this.properties = properties;
        this.userDirectory = userDirectory;
    }

    /** The id of the account whose Trash the request reads or changes. */
    UUID require(Jwt jwt) {
        if (!properties.enabled()) {
            throw new LocalizedStatusException(HttpStatus.SERVICE_UNAVAILABLE, new LocalizedMessage("feature.turnedOff"));
        }
        UUID id = UUID.fromString(jwt.getSubject());
        return userDirectory.findById(id)
                .orElseThrow(() -> new NotFoundException(new LocalizedMessage("user.current.notFound", id)))
                .id();
    }
}
