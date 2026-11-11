package com.flowcortex.content.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.content.api.FieldError;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ProblemDetail;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.i18n.FixedLocaleResolver;

class ContentProblemHandlerTest {

    private static final Locale VI = Locale.of("vi");

    private final ResourceBundleMessageSource messages = bundle();
    private final ContentProblemHandler handler = new ContentProblemHandler(messages, new FixedLocaleResolver(Locale.ENGLISH));

    private static ResourceBundleMessageSource bundle() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("i18n/messages");
        source.setDefaultEncoding(StandardCharsets.UTF_8.name());
        source.setFallbackToSystemLocale(false);
        source.setUseCodeAsDefaultMessage(false);
        return source;
    }

    private MockHttpServletResponse handle(ContentProblemException problem, String path, ProblemDetail[] out) {
        MockHttpServletResponse response = new MockHttpServletResponse();
        out[0] = handler.problem(problem, new MockHttpServletRequest("GET", path), response);
        return response;
    }

    @Test
    void everyFieldErrorKeyIsInBothBundles() throws IllegalAccessException {
        for (var field : FieldError.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
                String key = (String) field.get(null);
                assertThat(messages.getMessage(key, null, Locale.ENGLISH)).as(key).isNotBlank();
                assertThat(messages.getMessage(key, null, VI)).as(key).isNotBlank();
            }
        }
    }

    @Test
    void aNotFoundOnAPublicRouteIsNeverCacheable() {
        ProblemDetail[] problem = new ProblemDetail[1];

        MockHttpServletResponse response = handle(ContentProblemException.documentNotFound(),
                "/api/v1/content/documents/terms", problem);

        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(problem[0].getStatus()).isEqualTo(404);
    }

    @Test
    void anAdminNotFoundAndAPublicConflictAreLeftToTheDefaultCaching() {
        ProblemDetail[] problem = new ProblemDetail[1];

        MockHttpServletResponse admin = handle(ContentProblemException.documentNotFound(),
                "/api/v1/admin/content/documents/terms", problem);
        MockHttpServletResponse conflict = handle(ContentProblemException.nothingToPublish(),
                "/api/v1/content/documents/terms", problem);

        assertThat(admin.getHeader(HttpHeaders.CACHE_CONTROL)).isNull();
        assertThat(conflict.getHeader(HttpHeaders.CACHE_CONTROL)).isNull();
    }
}
