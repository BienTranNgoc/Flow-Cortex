package com.flowcortex.trash.web;

import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.user.api.UserDirectory;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * The JWT subject of a Trash route resolved into a live account. {@link UserDirectory#findById} refuses a suspended
 * account with 403, and a token can outlive its row (404). The 503 while the Trash is off is {@link TrashSwitchInterceptor}.
 */
@Component
class TrashCaller {

    private final UserDirectory userDirectory;

    TrashCaller(UserDirectory userDirectory) {
        this.userDirectory = userDirectory;
    }

    /** The id of the account whose Trash the request reads or changes. */
    UUID require(Jwt jwt) {
        UUID id = UUID.fromString(jwt.getSubject());
        return userDirectory.findById(id)
                .orElseThrow(() -> new NotFoundException(new LocalizedMessage("user.current.notFound", id)))
                .id();
    }
}
