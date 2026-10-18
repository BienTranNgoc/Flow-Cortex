package com.flowcortex.shared.web;

import java.util.Locale;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.LocaleResolver;

/**
 * The language of the request bound to the calling thread, for text the API sends outside a response
 * (a text message, an email). English when no request is bound, whatever the JVM locale.
 */
public class RequestLocale {

    private final LocaleResolver localeResolver;

    public RequestLocale(LocaleResolver localeResolver) {
        this.localeResolver = localeResolver;
    }

    public Locale current() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                ? localeResolver.resolveLocale(attributes.getRequest()) : LocaleConfig.DEFAULT_LOCALE;
    }
}
