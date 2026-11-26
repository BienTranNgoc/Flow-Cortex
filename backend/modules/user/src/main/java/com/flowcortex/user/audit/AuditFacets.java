package com.flowcortex.user.audit;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * The actions an audit row can carry and the resource types they name, read once from the routes'
 * {@code @AdminAudit} declarations so the viewer's selects never query the table and never drift from
 * the code. The reads that record themselves are not routes with the annotation, so they are listed.
 */
@Component
class AuditFacets {

    private static final List<String> RECORDED_READS =
            List.of("user.reveal_identity", "user.export", AuditLogExportService.ACTION);

    private final List<String> actions;
    private final List<String> resourceTypes;

    AuditFacets(@Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping handlerMapping) {
        Set<String> found = new TreeSet<>(RECORDED_READS);
        for (AuditRouteDescriptor route : AuditRouteDescriptor.from(handlerMapping)) {
            if (route.audit() != null) {
                found.add(route.audit().value());
            }
        }
        Set<String> types = new TreeSet<>();
        for (String action : found) {
            types.add(action.substring(0, action.indexOf('.')));
        }
        this.actions = List.copyOf(found);
        this.resourceTypes = List.copyOf(types);
    }

    List<String> actions() {
        return actions;
    }

    List<String> resourceTypes() {
        return resourceTypes;
    }
}
