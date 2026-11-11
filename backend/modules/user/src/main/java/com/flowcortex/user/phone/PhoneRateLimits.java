package com.flowcortex.user.phone;

import com.flowcortex.shared.ratelimit.RateLimitDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The send-code request limit per client IP on the shared registry. */
@Configuration(proxyBeanMethods = false)
class PhoneRateLimits {

    static final String PHONE_SEND_IP = "phone-send-ip";

    @Bean
    RateLimitDefinition phoneSendIp(PhoneSendLimits limits) {
        return new RateLimitDefinition(PHONE_SEND_IP, limits.perIp().capacity(), limits.perIp().period());
    }
}
