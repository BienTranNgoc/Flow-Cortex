package com.flowcortex.user.authz;

import com.flowcortex.shared.security.authz.Permission;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Refuses to let the application serve when an admin route has no posture, a handler carries two,
 * or a posture names a key the {@code permission} table does not hold: a mistake that would
 * otherwise show up as a denied or, worse, an unguarded route in production.
 */
@Component
class AuthzStartupCheck {

    private static final Logger LOG = LoggerFactory.getLogger(AuthzStartupCheck.class);

    private final RequestMappingHandlerMapping handlerMapping;
    private final JdbcTemplate jdbc;

    AuthzStartupCheck(@Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping handlerMapping,
            JdbcTemplate jdbc) {
        this.handlerMapping = handlerMapping;
        this.jdbc = jdbc;
    }

    @EventListener(ApplicationReadyEvent.class)
    void verify() {
        Set<String> seeded = new HashSet<>(jdbc.queryForList("SELECT key FROM permission", String.class));
        List<String> violations = AuthzInventory.violations(describeRoutes(), seeded);
        if (!violations.isEmpty()) {
            throw new IllegalStateException("Admin authorization inventory is unsound:\n- "
                    + String.join("\n- ", violations));
        }
        LOG.info("Admin authorization inventory verified against {} seeded permission keys", seeded.size());
    }

    private List<RouteDescriptor> describeRoutes() {
        List<RouteDescriptor> routes = new ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> mapping : handlerMapping.getHandlerMethods().entrySet()) {
            Method method = mapping.getValue().getMethod();
            int postures = PostureResolver.declaredCount(method);
            Set<String> keys = postures == 0 ? Set.of() : keysOf(PostureResolver.of(method));
            Set<String> patterns = mapping.getKey().getPatternValues();
            Set<String> verbs = mapping.getKey().getMethodsCondition().getMethods().stream()
                    .map(Enum::name)
                    .collect(Collectors.toCollection(TreeSet::new));
            if (verbs.isEmpty()) {
                verbs = Set.of("ANY");
            }
            for (String pattern : patterns) {
                for (String verb : verbs) {
                    routes.add(new RouteDescriptor(verb, pattern, postures, keys));
                }
            }
        }
        routes.sort(Comparator.comparing(RouteDescriptor::pattern).thenComparing(RouteDescriptor::method));
        return routes;
    }

    private static Set<String> keysOf(Posture posture) {
        Set<Permission> keys = switch (posture) {
            case Posture.AllOf allOf -> allOf.keys();
            case Posture.AnyOf anyOf -> anyOf.keys();
            default -> Set.of();
        };
        return keys.stream().map(Permission::key).collect(Collectors.toSet());
    }
}
