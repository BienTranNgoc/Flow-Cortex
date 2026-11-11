package com.flowcortex.mail.application;

import com.flowcortex.mail.api.MailKey;
import com.flowcortex.mail.links.UnsubscribeTokens;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MailProperties.class)
class MailConfig {

    @Bean
    UnsubscribeTokens unsubscribeTokens(MailProperties properties) {
        return new UnsubscribeTokens(properties.linkSecret());
    }

    /** Fails startup, naming the missing setting, when the catalog can mint links the deployment cannot sign. */
    @Bean
    InitializingBean linkSecretCheck(MailProperties properties,
            @Value("${flowcortex.app.public-url:}") String publicUrl) {
        return () -> LinkSecretRule.check(
                Arrays.stream(MailKey.values()).map(MailKey::category).collect(Collectors.toSet()),
                properties.linkSecret(), publicUrl);
    }
}
