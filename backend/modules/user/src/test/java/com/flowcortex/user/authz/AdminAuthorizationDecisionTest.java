package com.flowcortex.user.authz;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.shared.security.authz.Permission;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AdminAuthorizationDecisionTest {

    private static final CallerFacts SUPER_ADMIN = new CallerFacts(true, false, true, false, Set.of());
    private static final CallerFacts DEACTIVATED_SUPER_ADMIN = new CallerFacts(true, true, true, false, Set.of());
    private static final CallerFacts END_USER = new CallerFacts(false, false, false, false, Set.of());
    private static final CallerFacts DEACTIVATED_STAFF = new CallerFacts(false, true, true, false,
            Set.of(Permission.SUPPORT_TICKET_VIEW));

    private static CallerFacts staff(Permission... keys) {
        return new CallerFacts(false, false, true, false, Set.copyOf(EnumSet.of(keys[0], keys)));
    }

    private static CallerFacts withMfa(CallerFacts caller) {
        return new CallerFacts(caller.superAdmin(), caller.adminDisabled(), caller.consoleAccount(), true,
                caller.keys());
    }

    /** The second-factor step is off here, so these cases read as they did before it existed. */
    private static Verdict decide(Posture posture, CallerFacts caller) {
        return AdminAuthorizationDecision.decide(posture, caller, false);
    }

    private static Verdict decideRequiringMfa(Posture posture, CallerFacts caller) {
        return AdminAuthorizationDecision.decide(posture, caller, true);
    }

    @Test
    void noPostureIsDeniedEvenForASuperAdmin() {
        assertThat(decide(new Posture.None(), SUPER_ADMIN)).isEqualTo(Verdict.DENY);
    }

    @Test
    void aPostureConflictIsDeniedEvenForASuperAdmin() {
        assertThat(decide(new Posture.Conflict(), SUPER_ADMIN)).isEqualTo(Verdict.DENY);
    }

    @Test
    void publicAllowsEvenADeactivatedCaller() {
        assertThat(decide(new Posture.Public(), DEACTIVATED_SUPER_ADMIN)).isEqualTo(Verdict.ALLOW);
        assertThat(decide(new Posture.Public(), DEACTIVATED_STAFF)).isEqualTo(Verdict.ALLOW);
    }

    @Test
    void authenticatedOnlyAllowsADeactivatedCaller() {
        assertThat(decide(new Posture.AuthenticatedOnly(), DEACTIVATED_STAFF)).isEqualTo(Verdict.ALLOW);
        assertThat(decide(new Posture.AuthenticatedOnly(), END_USER)).isEqualTo(Verdict.ALLOW);
    }

    @Test
    void aDeactivatedSuperAdminIsRefusedOnAKeyPosture() {
        assertThat(decide(new Posture.AllOf(Set.of(Permission.USER_ACCOUNT_VIEW)), DEACTIVATED_SUPER_ADMIN))
                .isEqualTo(Verdict.ADMIN_DISABLED);
        assertThat(decide(new Posture.AnyOf(Set.of(Permission.USER_ACCOUNT_VIEW)), DEACTIVATED_SUPER_ADMIN))
                .isEqualTo(Verdict.ADMIN_DISABLED);
    }

    @Test
    void aDeactivatedStaffMemberIsRefusedEvenWithTheKey() {
        assertThat(decide(new Posture.AllOf(Set.of(Permission.SUPPORT_TICKET_VIEW)), DEACTIVATED_STAFF))
                .isEqualTo(Verdict.ADMIN_DISABLED);
    }

    @Test
    void aSuperAdminIsAllowedWithoutKeys() {
        assertThat(decide(new Posture.AllOf(Set.of(Permission.SYSTEM_ADMIN_ACCOUNT_MANAGE)), SUPER_ADMIN))
                .isEqualTo(Verdict.ALLOW);
        assertThat(decide(new Posture.AnyOf(Set.of(Permission.WORKFLOW_WORKFLOW_MANAGE)), SUPER_ADMIN))
                .isEqualTo(Verdict.ALLOW);
    }

    @Test
    void allOfNeedsEveryKey() {
        Posture both = new Posture.AllOf(Set.of(Permission.SUPPORT_TICKET_REPLY, Permission.SUPPORT_TICKET_CLOSE));

        assertThat(decide(both, staff(Permission.SUPPORT_TICKET_REPLY))).isEqualTo(Verdict.DENY);
        assertThat(decide(both, staff(Permission.SUPPORT_TICKET_REPLY, Permission.SUPPORT_TICKET_CLOSE)))
                .isEqualTo(Verdict.ALLOW);
    }

    @Test
    void anyOfNeedsOneKey() {
        Posture either = new Posture.AnyOf(Set.of(Permission.CONTENT_HELP_TOPIC_VIEW,
                Permission.CONTENT_HELP_ARTICLE_VIEW));

        assertThat(decide(either, staff(Permission.CONTENT_HELP_ARTICLE_VIEW))).isEqualTo(Verdict.ALLOW);
        assertThat(decide(either, staff(Permission.SUPPORT_TICKET_VIEW))).isEqualTo(Verdict.DENY);
    }

    @Test
    void anEndUserWithNoKeysIsDenied() {
        assertThat(decide(new Posture.AllOf(Set.of(Permission.WORKFLOW_RUN_VIEW)), END_USER))
                .isEqualTo(Verdict.DENY);
        assertThat(decide(new Posture.AnyOf(Set.of(Permission.WORKFLOW_RUN_VIEW)), END_USER))
                .isEqualTo(Verdict.DENY);
    }

    @Test
    void aKeyPostureWithNoKeysIsDeniedForEveryone() {
        assertThat(decide(new Posture.AllOf(Set.of()), staff(Permission.WORKFLOW_RUN_VIEW))).isEqualTo(Verdict.DENY);
        assertThat(decide(new Posture.AnyOf(Set.of()), SUPER_ADMIN)).isEqualTo(Verdict.DENY);
    }

    @Test
    void consoleAccountWithoutMfaIsMfaRequired() {
        Posture runs = new Posture.AllOf(Set.of(Permission.WORKFLOW_RUN_VIEW));

        assertThat(decideRequiringMfa(runs, SUPER_ADMIN)).isEqualTo(Verdict.MFA_REQUIRED);
        assertThat(decideRequiringMfa(runs, staff(Permission.WORKFLOW_RUN_VIEW))).isEqualTo(Verdict.MFA_REQUIRED);
        assertThat(decideRequiringMfa(new Posture.AnyOf(Set.of(Permission.WORKFLOW_RUN_VIEW)), SUPER_ADMIN))
                .isEqualTo(Verdict.MFA_REQUIRED);
        assertThat(decideRequiringMfa(runs, staff(Permission.SUPPORT_TICKET_VIEW))).isEqualTo(Verdict.MFA_REQUIRED);
    }

    @Test
    void consoleAccountWithMfaPassesToKeys() {
        Posture runs = new Posture.AllOf(Set.of(Permission.WORKFLOW_RUN_VIEW));

        assertThat(decideRequiringMfa(runs, withMfa(SUPER_ADMIN))).isEqualTo(Verdict.ALLOW);
        assertThat(decideRequiringMfa(runs, withMfa(staff(Permission.WORKFLOW_RUN_VIEW)))).isEqualTo(Verdict.ALLOW);
        assertThat(decideRequiringMfa(runs, withMfa(staff(Permission.SUPPORT_TICKET_VIEW)))).isEqualTo(Verdict.DENY);
    }

    @Test
    void endUserWithoutMfaStaysDenied() {
        assertThat(decideRequiringMfa(new Posture.AllOf(Set.of(Permission.WORKFLOW_RUN_VIEW)), END_USER))
                .isEqualTo(Verdict.DENY);
        assertThat(decideRequiringMfa(new Posture.AnyOf(Set.of(Permission.WORKFLOW_RUN_VIEW)), END_USER))
                .isEqualTo(Verdict.DENY);
    }

    @Test
    void deactivatedComesBeforeMfa() {
        Posture tickets = new Posture.AllOf(Set.of(Permission.SUPPORT_TICKET_VIEW));

        assertThat(decideRequiringMfa(tickets, DEACTIVATED_SUPER_ADMIN)).isEqualTo(Verdict.ADMIN_DISABLED);
        assertThat(decideRequiringMfa(tickets, DEACTIVATED_STAFF)).isEqualTo(Verdict.ADMIN_DISABLED);
    }

    @Test
    void aKeyPostureWithNoKeysIsDeniedBeforeMfa() {
        assertThat(decideRequiringMfa(new Posture.AllOf(Set.of()), SUPER_ADMIN)).isEqualTo(Verdict.DENY);
    }

    @Test
    void aPostureThatNeedsNoKeyNeverAsksForMfa() {
        assertThat(decideRequiringMfa(new Posture.AuthenticatedOnly(), SUPER_ADMIN)).isEqualTo(Verdict.ALLOW);
        assertThat(decideRequiringMfa(new Posture.Public(), staff(Permission.SUPPORT_TICKET_VIEW)))
                .isEqualTo(Verdict.ALLOW);
    }

    @Test
    void requirementOffSkipsTheStep() {
        Posture runs = new Posture.AllOf(Set.of(Permission.WORKFLOW_RUN_VIEW));

        assertThat(decide(runs, SUPER_ADMIN)).isEqualTo(Verdict.ALLOW);
        assertThat(decide(runs, staff(Permission.WORKFLOW_RUN_VIEW))).isEqualTo(Verdict.ALLOW);
    }

    @Test
    void effectiveKeysAreEveryKeyForASuperAdminTheRoleKeysForStaffAndNoneWhenDeactivated() {
        assertThat(SUPER_ADMIN.effectiveKeys()).containsExactlyInAnyOrder(Permission.values());
        assertThat(staff(Permission.WORKFLOW_RUN_VIEW).effectiveKeys()).containsExactly(Permission.WORKFLOW_RUN_VIEW);
        assertThat(DEACTIVATED_SUPER_ADMIN.effectiveKeys()).isEmpty();
        assertThat(DEACTIVATED_STAFF.effectiveKeys()).isEmpty();
        assertThat(END_USER.effectiveKeys()).isEmpty();
    }
}
