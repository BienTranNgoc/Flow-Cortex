package com.flowcortex.user.infrastructure;

import com.flowcortex.mail.api.MailRecipientDirectory;
import com.flowcortex.user.domain.AppUser;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Answers the mail module's question "where does this member's mail go" from the account row. */
@Component
class UserMailRecipients implements MailRecipientDirectory {

    private final AppUserRepository users;

    UserMailRecipients(AppUserRepository users) {
        this.users = users;
    }

    @Override
    public Optional<String> emailOf(UUID memberId) {
        return users.findById(memberId).map(AppUser::getEmail);
    }
}
