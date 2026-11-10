package com.flowcortex.user.application;

import com.flowcortex.shared.web.ConflictException;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.user.domain.AppUser;
import org.springframework.stereotype.Component;

/**
 * Keeps the account named by {@code flowcortex.registration.owner-email} an active {@code ADMIN}:
 * self-registration is refused while that account is not. Callers ask it only for a role change
 * away from {@code ADMIN}, a suspension or a deletion.
 */
@Component
public class RegistrationOwnerGuard {

    private final RegistrationProperties registration;

    RegistrationOwnerGuard(RegistrationProperties registration) {
        this.registration = registration;
    }

    /** @throws ConflictException {@code user.registrationOwner} when {@code target} is the registration owner */
    public void assertNotRegistrationOwner(AppUser target) {
        String ownerEmail = registration.ownerEmail() == null ? "" : registration.ownerEmail().trim();
        if (!ownerEmail.isEmpty() && ownerEmail.equalsIgnoreCase(target.getEmail().trim())) {
            throw new ConflictException(new LocalizedMessage("user.registrationOwner"));
        }
    }
}
