package com.flowcortex.shared.security.authz;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class PermissionTest {

    @Test
    void aKeySplitsIntoModuleFeatureAndAction() {
        Permission permission = Permission.PLATFORM_SMS_OUTBOX_VIEW;

        assertThat(permission.key()).isEqualTo("platform.sms_outbox.view");
        assertThat(permission.module()).isEqualTo("platform");
        assertThat(permission.feature()).isEqualTo("sms_outbox");
        assertThat(permission.action()).isEqualTo("view");
    }

    @Test
    void everyKeyIsThreeLowercaseSegmentsAndUnique() {
        assertThat(Permission.values())
                .allSatisfy(permission -> assertThat(permission.key()).matches("[a-z_]+\\.[a-z_]+\\.[a-z_]+"))
                .extracting(Permission::key)
                .doesNotHaveDuplicates();
    }

    @Test
    void onlySystemKeysAreSuperAdminOnly() {
        assertThat(Arrays.stream(Permission.values()).filter(Permission::superAdminOnly))
                .containsExactly(Permission.SYSTEM_ROLE_VIEW, Permission.SYSTEM_ROLE_MANAGE,
                        Permission.SYSTEM_ADMIN_ACCOUNT_VIEW, Permission.SYSTEM_ADMIN_ACCOUNT_MANAGE);
        assertThat(Permission.USER_ACCOUNT_VIEW.superAdminOnly()).isFalse();
    }

    @Test
    void auditAndPiiKeysAreNotSuperAdminOnly() {
        assertThat(Permission.fromKey("system.audit_log.view")).contains(Permission.SYSTEM_AUDIT_LOG_VIEW);
        assertThat(Permission.fromKey("system.audit_log.export")).contains(Permission.SYSTEM_AUDIT_LOG_EXPORT);
        assertThat(Permission.fromKey("user.account.view_pii")).contains(Permission.USER_ACCOUNT_VIEW_PII);
        assertThat(Permission.SYSTEM_AUDIT_LOG_VIEW.superAdminOnly()).isFalse();
        assertThat(Permission.SYSTEM_AUDIT_LOG_EXPORT.superAdminOnly()).isFalse();
        assertThat(Permission.USER_ACCOUNT_VIEW_PII.superAdminOnly()).isFalse();
    }

    @Test
    void newKeysComeLastInDeclarationOrder() {
        Permission[] all = Permission.values();

        assertThat(Arrays.copyOfRange(all, all.length - 3, all.length))
                .containsExactly(Permission.SYSTEM_AUDIT_LOG_VIEW, Permission.SYSTEM_AUDIT_LOG_EXPORT,
                        Permission.USER_ACCOUNT_VIEW_PII);
    }

    @Test
    void fromKeyFindsAKnownKeyAndNothingForAnUnknownOrMissingOne() {
        assertThat(Permission.fromKey("workflow.run.view")).contains(Permission.WORKFLOW_RUN_VIEW);
        assertThat(Permission.fromKey("workflow.run.delete")).isEmpty();
        assertThat(Permission.fromKey(null)).isEmpty();
    }
}
