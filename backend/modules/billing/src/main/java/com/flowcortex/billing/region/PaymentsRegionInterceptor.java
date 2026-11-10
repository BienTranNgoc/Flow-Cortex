package com.flowcortex.billing.region;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Locale;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Places the caller of a {@link PaymentsRegionGated} handler by the address the container resolved
 * ({@code getRemoteAddr()}, which honours the forwarded-header strategy) and refuses it under enforce.
 * The mode binds at startup.
 */
class PaymentsRegionInterceptor implements HandlerInterceptor, WebMvcConfigurer {

    private final PaymentsRegionService service;
    private final PaymentsRegionGateMode mode;
    private final RegionTelemetry telemetry;

    PaymentsRegionInterceptor(PaymentsRegionService service, PaymentsRegionProperties properties,
            RegionTelemetry telemetry) {
        this.service = service;
        this.mode = PaymentsRegionGateMode.parse(properties.mode());
        this.telemetry = telemetry;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/**");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (mode == PaymentsRegionGateMode.OFF || !gated(handler)) {
            return true;
        }
        RegionVerdict verdict = service.verdictFor(request.getRemoteAddr());
        boolean refused = !mode.allows(verdict);
        telemetry.record(verdict, mode, viSignal(request), refused);
        if (refused) {
            throw new PaymentsRegionUnavailableException();
        }
        return true;
    }

    private static boolean gated(Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return false;
        }
        return AnnotatedElementUtils.hasAnnotation(method.getMethod(), PaymentsRegionGated.class)
                || AnnotatedElementUtils.hasAnnotation(method.getBeanType(), PaymentsRegionGated.class);
    }

    private static boolean viSignal(HttpServletRequest request) {
        String language = request.getHeader(HttpHeaders.ACCEPT_LANGUAGE);
        return language != null && language.trim().toLowerCase(Locale.ROOT).startsWith("vi");
    }
}
