package com.flowcortex.user.phone;

import java.util.List;
import java.util.stream.Stream;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * The routes a held member can still reach: the session, the phone flow itself, the password setup
 * that runs before it, the switches the app reads before it can draw the wall, help and error intake.
 */
final class PhoneGateExemptions {

    private static final PathPatternParser PARSER = new PathPatternParser();

    private static final List<PathPattern> EXEMPT = Stream.of(
            "/api/v1/auth/**",
            "/api/v1/me/phone",
            "/api/v1/me/phone/**",
            "/api/v1/me/password",
            "/api/v1/features",
            "/api/v1/support/**",
            "/api/v1/client-errors")
            .map(PARSER::parse)
            .toList();

    private PhoneGateExemptions() {
    }

    static boolean isExempt(String path) {
        return isExempt(PathContainer.parsePath(path));
    }

    static boolean isExempt(PathContainer path) {
        return EXEMPT.stream().anyMatch(pattern -> pattern.matches(path));
    }
}
