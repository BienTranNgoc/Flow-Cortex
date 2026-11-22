package com.flowcortex.user.audit;

import com.flowcortex.shared.web.audit.AdminAudit;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * One mapped route as the audit coverage check sees it: its verb, patterns, path variables, the
 * {@link AdminAudit} on its handler (or null) and the element type of its {@code @RequestBody}
 * (or null when it has none).
 */
public record AuditRouteDescriptor(
        String method, List<String> patterns, Set<String> pathVariables, AdminAudit audit, Class<?> bodyType) {

    private static final Pattern PATH_VARIABLE = Pattern.compile("\\{([^}:/*]+)(?::[^}]*)?}");

    public AuditRouteDescriptor {
        patterns = List.copyOf(patterns);
        pathVariables = Set.copyOf(pathVariables);
    }

    public static List<AuditRouteDescriptor> from(RequestMappingHandlerMapping handlerMapping) {
        List<AuditRouteDescriptor> routes = new ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> mapping : handlerMapping.getHandlerMethods().entrySet()) {
            Method handler = mapping.getValue().getMethod();
            List<String> patterns = new ArrayList<>(new TreeSet<>(mapping.getKey().getPatternValues()));
            Set<String> verbs = mapping.getKey().getMethodsCondition().getMethods().stream()
                    .map(Enum::name)
                    .collect(Collectors.toCollection(TreeSet::new));
            if (verbs.isEmpty()) {
                verbs = Set.of("ANY");
            }
            AdminAudit audit = AnnotatedElementUtils.findMergedAnnotation(handler, AdminAudit.class);
            Class<?> body = bodyTypeOf(handler);
            for (String verb : verbs) {
                routes.add(new AuditRouteDescriptor(verb, patterns, pathVariablesOf(patterns), audit, body));
            }
        }
        routes.sort(Comparator.comparing((AuditRouteDescriptor r) -> r.patterns().toString())
                .thenComparing(AuditRouteDescriptor::method));
        return routes;
    }

    static Set<String> pathVariablesOf(List<String> patterns) {
        Set<String> names = new LinkedHashSet<>();
        for (String pattern : patterns) {
            Matcher matcher = PATH_VARIABLE.matcher(pattern);
            while (matcher.find()) {
                names.add(matcher.group(1));
            }
        }
        return names;
    }

    private static Class<?> bodyTypeOf(Method handler) {
        for (Parameter parameter : handler.getParameters()) {
            if (parameter.isAnnotationPresent(RequestBody.class)) {
                return elementType(parameter.getParameterizedType());
            }
        }
        return null;
    }

    private static Class<?> elementType(Type type) {
        if (type instanceof Class<?> clazz) {
            return clazz.isArray() ? clazz.getComponentType() : clazz;
        }
        if (type instanceof ParameterizedType parameterized && parameterized.getRawType() instanceof Class<?> raw) {
            if (Collection.class.isAssignableFrom(raw) && parameterized.getActualTypeArguments().length == 1) {
                return elementType(parameterized.getActualTypeArguments()[0]);
            }
            return raw;
        }
        return Object.class;
    }
}
