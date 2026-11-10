package com.flowcortex.chat.quota;

import com.flowcortex.chat.config.ChatProperties;
import com.flowcortex.shared.ratelimit.RateLimitDefinition;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Chat's per-minute limit per member, shared by every instance. */
@Configuration(proxyBeanMethods = false)
class ChatRateLimits {

    static final String MEMBER_MINUTE = "chat-member-minute";

    @Bean
    RateLimitDefinition chatMemberMinuteLimit(ChatProperties properties) {
        return definition(properties);
    }

    static RateLimitDefinition definition(ChatProperties properties) {
        return new RateLimitDefinition(MEMBER_MINUTE, properties.quota().perMinute(), Duration.ofMinutes(1));
    }
}
