package com.flowcortex.chat.web;

import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.user.api.CurrentUser;
import com.flowcortex.user.api.UserDirectory;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Resolves the JWT subject into the live user row. {@link UserDirectory#findById} refuses a
 * SUSPENDED user with 403, and a token can outlive the row it was minted for (404).
 */
@Component
public class ChatCaller {

    private final UserDirectory userDirectory;

    ChatCaller(UserDirectory userDirectory) {
        this.userDirectory = userDirectory;
    }

    public CurrentUser require(Jwt jwt) {
        UUID id = UUID.fromString(jwt.getSubject());
        return userDirectory.findById(id)
                .orElseThrow(() -> new NotFoundException(new LocalizedMessage("user.current.notFound", id)));
    }

    /** The role is read from the row, not the token, so an admin demoted since sign-in is refused at once. */
    public CurrentUser requireAdmin(Jwt jwt) {
        CurrentUser user = require(jwt);
        if (!"ADMIN".equals(user.role())) {
            throw new AccessDeniedException("User " + user.id() + " is not an ADMIN");
        }
        return user;
    }
}
