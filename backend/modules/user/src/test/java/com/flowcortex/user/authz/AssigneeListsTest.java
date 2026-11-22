package com.flowcortex.user.authz;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.shared.security.authz.Permission;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AssigneeListsTest {

    @Test
    void theSupportListRequiresTheAssignKey() {
        assertThat(AssigneeLists.callerKeyFor("support.ticket.reply")).contains(Permission.SUPPORT_TICKET_ASSIGN);
    }

    @Test
    void anUnofferedBlankOrMissingListResolvesToNothing() {
        assertThat(AssigneeLists.callerKeyFor("payment.refund.process")).isEmpty();
        assertThat(AssigneeLists.callerKeyFor("")).isEmpty();
        assertThat(AssigneeLists.callerKeyFor("  ")).isEmpty();
        assertThat(AssigneeLists.callerKeyFor(null)).isEmpty();
    }

    @Test
    void everyCallerKeyIsACatalogKeyAnAdminRoleCanHold() {
        assertThat(AssigneeLists.callerKeys()).isNotEmpty();
        for (Permission caller : AssigneeLists.callerKeys()) {
            assertThat(Permission.fromKey(caller.key())).isEqualTo(Optional.of(caller));
            assertThat(caller.superAdminOnly()).isFalse();
        }
    }
}
