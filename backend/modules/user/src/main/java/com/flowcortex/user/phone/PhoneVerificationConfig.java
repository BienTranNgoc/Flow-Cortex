package com.flowcortex.user.phone;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({PhoneSendLimits.class, PhoneRetentionProperties.class, PhoneHashProperties.class})
class PhoneVerificationConfig {
}
