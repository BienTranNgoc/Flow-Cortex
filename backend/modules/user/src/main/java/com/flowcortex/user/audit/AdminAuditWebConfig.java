package com.flowcortex.user.audit;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/** Declared as a bean, not scanned, so a controller slice test does not pull the interceptor in without its writer. */
@Configuration(proxyBeanMethods = false)
class AdminAuditWebConfig {

    @Bean
    AdminAuditInterceptor adminAuditInterceptor(JdbcAdminAuditWriter writer, AdminAuditActorReader actorReader,
            JsonMapper mapper) {
        return new AdminAuditInterceptor(writer, actorReader, mapper);
    }
}
