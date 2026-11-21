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
                .containsExactly(Permission.SYSTEM_ROLE_VIEW, Permission.SYSTEM_ADMIN_ACCOUNT_VIEW,
                        Permission.SYSTEM_ADMIN_ACCOUNT_MANAGE);
        assertThat(Permission.USER_ACCOUNT_VIEW.superAdminOnly()).isFalse();
    }

    @Test
    void fromKeyFindsAKnownKeyAndNothingForAnUnknownOrMissingOne() {
        assertThat(Permission.fromKey("workflow.run.view")).contains(Permission.WORKFLOW_RUN_VIEW);
        assertThat(Permission.fromKey("workflow.run.delete")).isEmpty();
        assertThat(Permission.fromKey(null)).isEmpty();
    }
}
