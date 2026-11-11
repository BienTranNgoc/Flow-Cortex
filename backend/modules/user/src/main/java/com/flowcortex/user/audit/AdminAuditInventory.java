package com.flowcortex.user.audit;

import com.flowcortex.shared.web.audit.AdminAudit;
import com.flowcortex.user.authz.AuthzInventory;
import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Checks every mapped route against the audit rules: a governed write route is declared, and each
 * declaration is well-formed. The result lists each breach and is empty when sound.
 */
public final class AdminAuditInventory {

    private static final Set<String> WRITE_VERBS = Set.of("POST", "PUT", "PATCH", "DELETE");
    private static final Pattern ACTION = Pattern.compile("[a-z0-9_]+\\.[a-z0-9_]+");
    private static final Set<String> TREE_TYPES =
            Set.of("tools.jackson.databind.JsonNode", "com.fasterxml.jackson.databind.JsonNode");
    private static final List<String> SECRET_MARKERS = List.of("password", "secret", "token", "credential");

    private AdminAuditInventory() {}

    public static List<String> violations(List<AuditRouteDescriptor> routes) {
        List<String> violations = new ArrayList<>();
        for (AuditRouteDescriptor route : routes) {
            String label = route.method() + " " + String.join(", ", route.patterns());
            AdminAudit audit = route.audit();
            if (audit == null) {
                if (WRITE_VERBS.contains(route.method())
                        && route.patterns().stream().anyMatch(AuthzInventory::isGoverned)) {
                    violations.add(label + " is an admin write route with no @AdminAudit declaration");
                }
                continue;
            }
            checkDeclaration(label, route, audit, violations);
        }
        return violations;
    }

    private static void checkDeclaration(
            String label, AuditRouteDescriptor route, AdminAudit audit, List<String> violations) {
        if (!ACTION.matcher(audit.value()).matches()) {
            violations.add(label + " declares action '" + audit.value()
                    + "' which is not <resource_type>.<verb> in lowercase letters, digits and underscores");
        }
        if (!audit.resourceParam().isEmpty()
                && !route.pathVariables().isEmpty()
                && !route.pathVariables().contains(audit.resourceParam())) {
            violations.add(label + " declares resource parameter '" + audit.resourceParam()
                    + "' which is not a path variable of the route");
        }
        for (String param : audit.params()) {
            if (!route.pathVariables().contains(param)) {
                violations.add(label + " declares path parameter '" + param + "' which the route lacks");
            }
        }
        if (audit.capture().length == 0) {
            return;
        }
        if (route.bodyType() == null) {
            violations.add(label + " captures " + Arrays.toString(audit.capture()) + " but has no request body");
            return;
        }
        Set<String> fields = bodyFields(route.bodyType());
        for (String name : audit.capture()) {
            if (fields == null) {
                violations.add(label + " captures '" + name + "' from a body of type "
                        + route.bodyType().getSimpleName() + " whose fields cannot be checked");
            } else if (!fields.contains(name)) {
                violations.add(label + " captures '" + name + "' which is not a field of "
                        + route.bodyType().getSimpleName());
            }
            if (isSecretLike(name)) {
                violations.add(label + " captures '" + name + "' whose name looks secret");
            }
        }
    }

    private static boolean isSecretLike(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return SECRET_MARKERS.stream().anyMatch(lower::contains);
    }

    /** A map, an untyped object or a Jackson tree (either Jackson generation) has no declared fields. */
    private static boolean isFreeForm(Class<?> type) {
        if (Map.class.isAssignableFrom(type) || type == Object.class) {
            return true;
        }
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            if (TREE_TYPES.contains(c.getName())) {
                return true;
            }
        }
        return false;
    }

    /** Field names of a record or bean; null when the type is a free-form tree that cannot be checked. */
    private static Set<String> bodyFields(Class<?> type) {
        if (isFreeForm(type)) {
            return null;
        }
        Set<String> names = new TreeSet<>();
        if (type.isRecord()) {
            for (RecordComponent component : type.getRecordComponents()) {
                names.add(component.getName());
            }
            return names;
        }
        try {
            for (PropertyDescriptor property : Introspector.getBeanInfo(type, Object.class).getPropertyDescriptors()) {
                names.add(property.getName());
            }
        } catch (IntrospectionException e) {
            return null;
        }
        return names;
    }
}
