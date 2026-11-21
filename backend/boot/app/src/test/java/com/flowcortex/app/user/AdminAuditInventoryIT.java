package com.flowcortex.app.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.AbstractPostgresBootTest;
import com.flowcortex.user.audit.AdminAuditInventory;
import com.flowcortex.user.audit.AuditRouteDescriptor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** The coverage check over the booted application: every governed write route is audited and well declared. */
@SpringBootTest(webEnvironment = WebEnvironment.MOCK)
class AdminAuditInventoryIT extends AbstractPostgresBootTest {

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Test
    void everyGovernedWriteRouteIsAuditedAndWellDeclared() {
        assertThat(AdminAuditInventory.violations(AuditRouteDescriptor.from(handlerMapping))).isEmpty();
    }

    @Test
    void theInventoryReadsTheLiveRoutes() {
        assertThat(AuditRouteDescriptor.from(handlerMapping))
                .anyMatch(route -> route.audit() != null && route.patterns().contains("/api/v1/users/{id}"));
    }
}
