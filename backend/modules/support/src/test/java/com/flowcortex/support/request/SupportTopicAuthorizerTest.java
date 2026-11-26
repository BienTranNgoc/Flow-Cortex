package com.flowcortex.support.request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowcortex.shared.security.authz.Permission;
import com.flowcortex.user.api.AdminPermissions;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SupportTopicAuthorizerTest {

    private final AdminPermissions adminPermissions = mock(AdminPermissions.class);
    private final RequestRepository requests = mock(RequestRepository.class);
    private final SupportTopicAuthorizer authorizer = new SupportTopicAuthorizer(adminPermissions, requests);
    private final UUID caller = UUID.randomUUID();

    @Test
    void claimsOnlyCanonicalRequestNumbers() {
        assertThat(authorizer.supports("support:request:1")).isTrue();
        assertThat(authorizer.supports("support:request:42")).isTrue();
        assertThat(authorizer.supports("support:request:9223372036854775807")).isTrue();

        for (String unclaimed : new String[] {"support:request:0", "support:request:042", "support:request:-1",
                "support:request:+4", "support:request:abc", "support:request:", "support:request:9223372036854775808",
                "support:request:4 ", "run:42", "support:42", "user", null}) {
            assertThat(authorizer.supports(unclaimed)).as("claims %s", unclaimed).isFalse();
        }
    }

    @Test
    void authorizesAViewHolderForAnExistingRequest() {
        when(adminPermissions.holds(caller, Permission.SUPPORT_TICKET_VIEW)).thenReturn(true);
        when(requests.existsByNumber(42)).thenReturn(true);

        assertThat(authorizer.authorize(caller, "support:request:42")).isTrue();
    }

    @Test
    void refusesAMissingRequest() {
        when(adminPermissions.holds(caller, Permission.SUPPORT_TICKET_VIEW)).thenReturn(true);
        when(requests.existsByNumber(42)).thenReturn(false);

        assertThat(authorizer.authorize(caller, "support:request:42")).isFalse();
    }

    @Test
    void refusesACallerWithoutTheViewKeyWithoutReadingTheRequest() {
        when(adminPermissions.holds(caller, Permission.SUPPORT_TICKET_VIEW)).thenReturn(false);
        when(requests.existsByNumber(42)).thenReturn(true);

        assertThat(authorizer.authorize(caller, "support:request:42")).isFalse();
        verify(requests, never()).existsByNumber(anyLong());
    }

    @Test
    void isRevocable() {
        assertThat(authorizer.revocable()).isTrue();
    }
}
