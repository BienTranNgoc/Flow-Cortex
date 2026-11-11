package com.flowcortex.user.phone;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * Fails startup when the phone wall is on while phone verification is off: every member would be held
 * with no way to verify. Both flags bind at startup, so a restart is the only way out.
 */
@Configuration(proxyBeanMethods = false)
public class PhoneGateConfig {

    PhoneGateConfig(@Value("${flowcortex.phone-gate.enabled:false}") boolean gateEnabled,
            @Value("${flowcortex.phone-verification.enabled:false}") boolean verificationEnabled) {
        if (gateEnabled && !verificationEnabled) {
            throw new IllegalStateException("flowcortex.phone-gate.enabled is true but "
                    + "flowcortex.phone-verification.enabled is false: a held member could never verify a number");
        }
    }
}
