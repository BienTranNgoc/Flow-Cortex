package com.flowcortex.shared.security.authz;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class PermissionTest {

    @Test
    void aKeySplitsIntoItsModuleFeatureAndAction() {
        Permission permission = Permission.PLATFORM_SMS_OUTBOX_VIEW;

        assertThat(permission.key()).isEqualTo("platform.sms_outbox.view");
        assertThat(permission.module()).isEqualTo("platform");
        assertThat(permission.feature()).isEqualTo("sms_outbox");
        assertThat(permission.action()).isEqualTo("view");
    }

    @Test
    void everyKeyResolvesBackToItsConstant() {
        for (Permission permission : Permission.values()) {
            assertThat(Permission.fromKey(permission.key())).contains(permission);
            assertThat(permission.key()).isEqualTo(
                    permission.module() + "." + permission.feature() + "." + permission.action());
        }
    }

    @Test
    void anUnknownOrMissingKeyResolvesToNothing() {
        assertThat(Permission.fromKey("platform.setting.delete")).isEmpty();
        assertThat(Permission.fromKey(null)).isEmpty();
    }

    @Test
    void onlyTheSystemKeysAreReservedForTheSuperAdmin() {
        assertThat(Arrays.stream(Permission.values()).filter(Permission::superAdminOnly))
                .containsExactly(Permission.SYSTEM_ROLE_VIEW, Permission.SYSTEM_ADMIN_ACCOUNT_VIEW,
                        Permission.SYSTEM_ADMIN_ACCOUNT_MANAGE);
    }
}
