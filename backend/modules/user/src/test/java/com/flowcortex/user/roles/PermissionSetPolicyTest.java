package com.flowcortex.user.roles;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.user.roles.PermissionSetPolicy.CatalogKey;
import com.flowcortex.user.roles.PermissionSetPolicy.Violation;
import com.flowcortex.user.roles.PermissionSetPolicy.ViolationCode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PermissionSetPolicyTest {

    private static final List<CatalogKey> CATALOG = List.of(
            new CatalogKey("support.ticket.view", false),
            new CatalogKey("support.ticket.reply", false),
            new CatalogKey("workflow.workflow.manage", false),
            new CatalogKey("system.role.manage", true));

    private static Optional<Violation> check(List<String> keys) {
        return PermissionSetPolicy.check(keys, CATALOG);
    }

    @Test
    void anUnknownKeyIsNamed() {
        Optional<Violation> violation = check(List.of("support.ticket.view", "support.ticket.nuke"));

        assertThat(violation).isPresent();
        assertThat(violation.get().code()).isEqualTo(ViolationCode.PERMISSION_UNKNOWN);
        assertThat(violation.get().subjects()).containsExactly("support.ticket.nuke");
    }

    @Test
    void anUnknownKeyWinsOverASuperAdminOnlyKey() {
        Optional<Violation> violation = check(List.of("system.role.manage", "nope.nope.nope"));

        assertThat(violation).map(Violation::code).contains(ViolationCode.PERMISSION_UNKNOWN);
    }

    @Test
    void aSuperAdminOnlyKeyIsRefused() {
        Optional<Violation> violation = check(List.of("system.role.manage"));

        assertThat(violation).map(Violation::code).contains(ViolationCode.PERMISSION_SUPER_ADMIN_ONLY);
        assertThat(violation.get().subjects()).containsExactly("system.role.manage");
    }

    @Test
    void aSuperAdminOnlyKeyWinsOverAMissingView() {
        Optional<Violation> violation = check(List.of("support.ticket.reply", "system.role.manage"));

        assertThat(violation).map(Violation::code).contains(ViolationCode.PERMISSION_SUPER_ADMIN_ONLY);
    }

    @Test
    void anActionWithoutItsViewNamesTheFeature() {
        Optional<Violation> violation = check(List.of("support.ticket.reply"));

        assertThat(violation).map(Violation::code).contains(ViolationCode.PERMISSION_SET_INCONSISTENT);
        assertThat(violation.get().subjects()).containsExactly("support.ticket");
    }

    @Test
    void anActionWithItsViewIsValid() {
        assertThat(check(List.of("support.ticket.reply", "support.ticket.view"))).isEmpty();
    }

    @Test
    void aFeatureWithNoViewActionIsValidAlone() {
        assertThat(check(List.of("workflow.workflow.manage"))).isEmpty();
    }

    @Test
    void theSameSetIsInconsistentOnceTheCatalogGainsTheView() {
        List<CatalogKey> grown = new ArrayList<>(CATALOG);
        grown.add(new CatalogKey("workflow.workflow.view", false));

        Optional<Violation> violation = PermissionSetPolicy.check(List.of("workflow.workflow.manage"), grown);

        assertThat(violation).map(Violation::code).contains(ViolationCode.PERMISSION_SET_INCONSISTENT);
        assertThat(violation.get().subjects()).containsExactly("workflow.workflow");
    }

    @Test
    void duplicatesCountOnce() {
        assertThat(check(List.of("support.ticket.view", "support.ticket.view", "support.ticket.reply"))).isEmpty();
        assertThat(check(List.of("support.ticket.reply", "support.ticket.reply")).get().subjects())
                .containsExactly("support.ticket");
    }

    @Test
    void anEmptySetIsValid() {
        assertThat(check(List.of())).isEmpty();
        assertThat(PermissionSetPolicy.check(Set.of(), CATALOG)).isEmpty();
    }
}
