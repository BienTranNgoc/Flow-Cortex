package com.flowcortex.mail.api;

import java.util.Optional;
import java.util.UUID;

/** Port the member-owning module implements: the current address of a member, if the member exists. */
public interface MailRecipientDirectory {

    Optional<String> emailOf(UUID memberId);
}
