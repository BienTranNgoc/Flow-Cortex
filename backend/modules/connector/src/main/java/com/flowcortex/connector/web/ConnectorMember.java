package com.flowcortex.connector.web;

import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.user.api.UserDirectory;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/** Resolves the signed-in member of a connector route; a suspended member fails with 403 inside the lookup. */
@Component
class ConnectorMember {

    private final UserDirectory userDirectory;

    ConnectorMember(UserDirectory userDirectory) {
        this.userDirectory = userDirectory;
    }

    UUID id(Jwt jwt) {
        UUID id = UUID.fromString(jwt.getSubject());
        return userDirectory.findById(id)
                .orElseThrow(() -> new NotFoundException(new LocalizedMessage("user.current.notFound", id)))
                .id();
    }
}
