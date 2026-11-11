package com.flowcortex.onboarding.web;

import com.flowcortex.onboarding.application.SampleAuditLog;
import com.flowcortex.onboarding.application.SampleAuditLog.Outcome;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.security.Principal;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.stereotype.Component;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.ModelAndView;

/**
 * Leaves the audit line for an admin write to the sample that is refused while its request is being
 * bound (bean validation, an unreadable body), before the service ever runs. It only
 * observes: it answers {@code null} so the shared handler still builds the 400.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class SampleBindingRefusalLog implements HandlerExceptionResolver {

    private static final String SAMPLE_OPERATION = "updateAdminOnboardingSample";
    private static final String TRANSLATION_OPERATION = "updateAdminOnboardingSampleTranslation";

    @Override
    public ModelAndView resolveException(HttpServletRequest request, HttpServletResponse response, Object handler,
            Exception ex) {
        if (!(handler instanceof HandlerMethod method)
                || method.getBeanType() != AdminOnboardingSampleController.class) {
            return null;
        }
        Operation operation = method.getMethodAnnotation(Operation.class);
        String reason = reason(ex);
        if (operation == null || reason == null) {
            return null;
        }
        UUID adminId = adminId(request.getUserPrincipal());
        if (SAMPLE_OPERATION.equals(operation.operationId())) {
            SampleAuditLog.sample(Outcome.REFUSED, adminId, workflowId(ex), reason);
        } else if (TRANSLATION_OPERATION.equals(operation.operationId())) {
            SampleAuditLog.translation(Outcome.REFUSED, adminId, locale(request), reason);
        }
        return null;
    }

    /** Fixed codes and bean field names only, so no request text reaches the log. */
    private static String reason(Exception ex) {
        if (ex instanceof MethodArgumentNotValidException invalid) {
            return "invalid:" + invalid.getBindingResult().getFieldErrors().stream().map(FieldError::getField)
                    .distinct().sorted().collect(Collectors.joining(","));
        }
        if (ex instanceof HttpMessageNotReadableException) {
            return "unreadable";
        }
        return null;
    }

    private static UUID workflowId(Exception ex) {
        if (ex instanceof MethodArgumentNotValidException invalid
                && invalid.getBindingResult().getTarget() instanceof AdminOnboardingSampleUpdate body) {
            return parse(body.workflowId());
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static String locale(HttpServletRequest request) {
        Object variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        return variables instanceof Map<?, ?> map ? (String) ((Map<String, String>) map).get("locale") : null;
    }

    private static UUID adminId(Principal principal) {
        return principal == null ? null : parse(principal.getName());
    }

    private static UUID parse(String text) {
        if (text == null) {
            return null;
        }
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException notAnId) {
            return null;
        }
    }
}
