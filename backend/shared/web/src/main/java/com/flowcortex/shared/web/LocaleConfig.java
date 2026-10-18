package com.flowcortex.shared.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.http.HttpHeaders;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The API's languages: {@code Accept-Language} picks English or Vietnamese, anything else is English.
 * A {@link WebMvcConfigurer} so {@code @WebMvcTest} slices load the same beans as the application.
 */
@Configuration(proxyBeanMethods = false)
public class LocaleConfig implements WebMvcConfigurer {

    static final Locale DEFAULT_LOCALE = Locale.ENGLISH;
    static final Locale VIETNAMESE = Locale.of("vi");

    private static final List<Locale> SUPPORTED = List.of(DEFAULT_LOCALE, VIETNAMESE);

    @Bean(name = "localeResolver")
    LocaleResolver localeResolver() {
        return new AcceptLanguageHeaderResolver();
    }

    @Bean(name = "messageSource")
    MessageSource messageSource() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("i18n/messages");
        source.setDefaultEncoding(StandardCharsets.UTF_8.name());
        // Otherwise a server whose JVM locale is vi would answer header-less requests in Vietnamese.
        source.setFallbackToSystemLocale(false);
        source.setUseCodeAsDefaultMessage(false);
        return source;
    }

    /** The supported language the header ranks highest; English when it names none or does not parse. */
    private static Locale resolve(String acceptLanguage) {
        if (acceptLanguage == null || acceptLanguage.isBlank()) {
            return DEFAULT_LOCALE;
        }
        try {
            Locale match = Locale.lookup(Locale.LanguageRange.parse(acceptLanguage), SUPPORTED);
            return match == null ? DEFAULT_LOCALE : match;
        } catch (IllegalArgumentException ex) {
            return DEFAULT_LOCALE;
        }
    }

    /**
     * Reads the header text only: {@code request.getLocale()} falls back to the JVM default for an empty
     * or unparseable header, which would make the answer depend on the server.
     */
    private static final class AcceptLanguageHeaderResolver implements LocaleResolver {

        @Override
        public Locale resolveLocale(HttpServletRequest request) {
            return resolve(request.getHeader(HttpHeaders.ACCEPT_LANGUAGE));
        }

        @Override
        public void setLocale(HttpServletRequest request, HttpServletResponse response, Locale locale) {
            throw new UnsupportedOperationException("The language follows the Accept-Language header");
        }
    }
}
