package com.flowcortex.user.roles;

import com.flowcortex.settings.api.SettingDefinition;
import com.flowcortex.settings.api.SettingKey;
import com.flowcortex.settings.api.SettingScope;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The role-edit kill switch: on by default, in its own scope so only the super-admin route serves it. The
 * configured layer is {@code flowcortex.authz.rbac-mutations.enabled}; an unset or empty value binds
 * {@code null} and defers to the stored value, {@code false} forces the switch off.
 */
@Configuration(proxyBeanMethods = false)
public class RbacRuntimeSettings {

    public static final SettingKey<Boolean> MUTATIONS_ENABLED = SettingKey.switchKey("user.rbac.mutations_enabled");

    static SettingDefinition<Boolean> definition(Supplier<Boolean> configured) {
        return SettingDefinition.switchKey(MUTATIONS_ENABLED.name(), true, configured)
                .inScope(SettingScope.RBAC_RUNTIME);
    }

    @Bean
    SettingDefinition<Boolean> rbacMutationsSetting(
            @Value("${flowcortex.authz.rbac-mutations.enabled:#{null}}") Boolean configured) {
        return definition(() -> configured);
    }
}
