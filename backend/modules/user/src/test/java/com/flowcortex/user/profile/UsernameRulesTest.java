package com.flowcortex.user.profile;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.flowcortex.user.domain.AppUser;
import com.flowcortex.user.infrastructure.AppUserRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class UsernameRulesTest {

    private final AppUserRepository users = mock(AppUserRepository.class);
    private final UsernameHoldRepository holds = mock(UsernameHoldRepository.class);
    private final UsernameRules rules = new UsernameRules(users, holds);

    private static AppUser withUsername(String username) {
        return AppUser.selfRegistered(UUID.randomUUID(), UUID.randomUUID(), "a@example.com", "hash", "An", "Tran",
                username, Instant.now());
    }

    @Test
    void changeLocksTheReleasedNameAndTheNewNameInSortedOrder() {
        rules.change(withUsername("bob"), "alpha", Instant.now());

        InOrder order = inOrder(users);
        order.verify(users).lockUsername("alpha");
        order.verify(users).lockUsername("bob");
    }

    @Test
    void changeLocksTheLowerNameFirstWhicheverWayTheChangeGoes() {
        rules.change(withUsername("alpha"), "bob", Instant.now());

        InOrder order = inOrder(users);
        order.verify(users).lockUsername("alpha");
        order.verify(users).lockUsername("bob");
    }

    @Test
    void changeOfAnAccountWithoutAUsernameLocksOnlyTheNewName() {
        rules.change(withUsername(null), "bob", Instant.now());

        verify(users).lockUsername("bob");
        verify(users, never()).lockUsername(null);
    }

    @Test
    void resendingTheStoredNameLocksItOnce() {
        rules.change(withUsername("bob"), "bob", Instant.now());

        verify(users).lockUsername("bob");
    }
}
