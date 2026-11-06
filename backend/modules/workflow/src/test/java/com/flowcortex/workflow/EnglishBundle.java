package com.flowcortex.workflow;

import com.flowcortex.shared.web.LocaleConfig;
import java.util.Locale;
import org.springframework.context.MessageSource;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.web.ErrorResponse;

/** Resolves a message through the application's own bundle configuration, as a request without a language gets it. */
public final class EnglishBundle {

    private static final MessageSource MESSAGES = new AnnotationConfigApplicationContext(LocaleConfig.class);

    private EnglishBundle() {
    }

    public static String english(MessageSourceResolvable message) {
        return MESSAGES.getMessage(message, Locale.ENGLISH);
    }

    public static String english(ErrorResponse error) {
        return MESSAGES.getMessage(error.getDetailMessageCode(), error.getDetailMessageArguments(), Locale.ENGLISH);
    }
}
