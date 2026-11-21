package com.flowcortex.user.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowcortex.shared.web.ConflictException;
import com.flowcortex.user.domain.AppUser;
import com.flowcortex.user.domain.Role;
import com.flowcortex.user.domain.UserStatus;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RegistrationOwnerGuardTest {

    private static final RateLimit HOURLY = new RateLimit(5, Duration.ofHours(1));

    @Test
    void theRegistrationOwnerIsRefused() {
        RegistrationOwnerGuard guard = guard("an@nodi.vn");

        assertThatThrownBy(() -> guard.assertNotRegistrationOwner(admin("an@nodi.vn")))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.getDetail().code()).isEqualTo("user.registrationOwner"));
    }

    @Test
    void anotherAdminIsAllowed() {
        assertThatCode(() -> guard("an@nodi.vn").assertNotRegistrationOwner(admin("binh@nodi.vn")))
                .doesNotThrowAnyException();
    }

    @Test
    void aBlankOwnerPropertyAllowsEveryone() {
        assertThatCode(() -> guard("  ").assertNotRegistrationOwner(admin("an@nodi.vn"))).doesNotThrowAnyException();
        assertThatCode(() -> guard(null).assertNotRegistrationOwner(admin("an@nodi.vn"))).doesNotThrowAnyException();
    }

    @Test
    void theMatchIgnoresCaseAndSurroundingSpaces() {
        RegistrationOwnerGuard guard = guard("  An@Nodi.VN ");

        assertThatThrownBy(() -> guard.assertNotRegistrationOwner(admin("an@nodi.vn")))
                .isInstanceOf(ConflictException.class);
    }

    private static RegistrationOwnerGuard guard(String ownerEmail) {
        return new RegistrationOwnerGuard(new RegistrationProperties(ownerEmail, HOURLY, HOURLY));
    }

    private static AppUser admin(String email) {
        UUID id = UUID.randomUUID();
        return new AppUser(id, id, email, "$2a$hash", "Admin", Role.ADMIN, UserStatus.ACTIVE);
    }
}
