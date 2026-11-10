package com.flowcortex.user.audit;

import com.flowcortex.shared.web.audit.AdminAudit;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Records one {@code admin_audit_event} row for every request to an {@code @AdminAudit} route that
 * got past the permission decision and the kill switch. It runs last so a request refused earlier
 * never reaches {@code afterCompletion}.
 */
@Component
class AdminAuditInterceptor implements HandlerInterceptor, WebMvcConfigurer {

    static final int MAX_USER_AGENT_LENGTH = 512;
    private static final Logger LOG = LoggerFactory.getLogger(AdminAuditInterceptor.class);

    private final JdbcAdminAuditWriter writer;
    private final JsonMapper mapper;

    AdminAuditInterceptor(JdbcAdminAuditWriter writer, JsonMapper mapper) {
        this.writer = writer;
        this.mapper = mapper;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/**").order(Ordered.LOWEST_PRECEDENCE);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        AdminAudit annotation = method.getMethodAnnotation(AdminAudit.class);
        UUID actor = annotation == null ? null : actorId();
        if (actor != null) {
            request.setAttribute(PendingAudit.ATTRIBUTE, new PendingAudit(
                    annotation, actor, request.getRemoteAddr(), cut(request.getHeader("User-Agent")),
                    pathVariables(request)));
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
            Exception ex) {
        if (!(request.getAttribute(PendingAudit.ATTRIBUTE) instanceof PendingAudit pending)) {
            return;
        }
        request.removeAttribute(PendingAudit.ATTRIBUTE);
        try {
            record(pending, response.getStatus(), ex);
        } catch (RuntimeException e) {
            LOG.error("Admin audit entry for {} could not be built", pending.annotation().value(), e);
        }
    }

    private void record(PendingAudit pending, int status, Exception unresolved) {
        AdminAudit annotation = pending.annotation();
        Throwable error = pending.exception() != null ? pending.exception() : unresolved;
        boolean failed = error != null || status >= 400;
        Map<String, String> declaredParams = new LinkedHashMap<>();
        for (String name : annotation.params()) {
            String value = pending.pathVariables().get(name);
            if (value != null) {
                declaredParams.put(name, value);
            }
        }
        JsonNode summary = ChangeSummary.build(
                pending.requestBody(), annotation.value(), annotation.capture(), declaredParams, mapper);
        AuditResource.Result resource = AuditResource.of(
                annotation.resourceParam(), pending.pathVariables(), pending.responseBody(), mapper);
        writer.write(new AdminAuditEntry(
                pending.actorUserId(),
                annotation.value(),
                resourceType(annotation.value()),
                resource.id(),
                resource.label(),
                summary,
                failed ? AdminAuditEntry.Status.FAILED : AdminAuditEntry.Status.SUCCESS,
                failed ? AuditErrorCode.of(error, status) : null,
                pending.ip(),
                pending.userAgent()));
    }

    private static String resourceType(String action) {
        int dot = action.indexOf('.');
        return dot < 0 ? action : action.substring(0, dot);
    }

    private static UUID actorId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken token && token.isAuthenticated()) {
            try {
                return UUID.fromString(token.getToken().getSubject());
            } catch (IllegalArgumentException | NullPointerException e) {
                return null;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> pathVariables(HttpServletRequest request) {
        Object vars = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        return vars instanceof Map<?, ?> map ? Map.copyOf((Map<String, String>) map) : Map.of();
    }

    private static String cut(String value) {
        return value == null || value.length() <= MAX_USER_AGENT_LENGTH
                ? value
                : value.substring(0, MAX_USER_AGENT_LENGTH);
    }
}
