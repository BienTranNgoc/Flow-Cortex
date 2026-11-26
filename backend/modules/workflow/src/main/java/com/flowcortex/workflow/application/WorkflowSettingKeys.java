package com.flowcortex.workflow.application;

import com.flowcortex.settings.api.SettingDefinition;
import com.flowcortex.settings.api.SettingKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The workflow module's runtime settings. */
@Configuration(proxyBeanMethods = false)
class WorkflowSettingKeys {

    static final SettingKey<Boolean> RUN_CANCELLATION_ENABLED =
            SettingKey.switchKey("workflow.run_cancellation.enabled");

    /** The environment is the capability gate: configured off vetoes whatever an admin stored. */
    @Bean
    SettingDefinition<Boolean> runCancellationEnabledSetting(
            @Value("${flowcortex.workflow.cancel.enabled:false}") boolean configured) {
        return SettingDefinition.switchKey(RUN_CANCELLATION_ENABLED.name(), true, () -> configured);
    }
}
