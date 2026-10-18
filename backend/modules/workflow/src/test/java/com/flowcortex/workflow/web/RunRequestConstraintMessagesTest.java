package com.flowcortex.workflow.web;

import static java.util.stream.Collectors.toMap;
import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.workflow.EnglishBundle;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.Conventions;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

/**
 * The custom {@code @AssertTrue} sentences are bundle keys named after the request record and its
 * property; renaming either would silently fall back to the generic "must be true".
 */
class RunRequestConstraintMessagesTest {

    private static final LocalValidatorFactoryBean VALIDATOR = new LocalValidatorFactoryBean();

    @BeforeAll
    static void startValidator() {
        VALIDATOR.afterPropertiesSet();
    }

    @AfterAll
    static void closeValidator() {
        VALIDATOR.close();
    }

    @Test
    void aStartRunRequestKeepsTodaysCustomSentences() {
        UUID upload = UUID.randomUUID();

        assertThat(englishErrors(new StartRunRequest(null, upload, List.of(upload, upload), null, null)))
                .containsEntry("uploadSelectionUnambiguous", "Use uploadId or uploadIds, not both")
                .containsEntry("uploadOrderDistinct", "uploadIds must not contain duplicates");
    }

    @Test
    void aProductVideoRunRequestKeepsTodaysCustomSentences() {
        UUID upload = UUID.randomUUID();
        ProductVideoRunRequest request = new ProductVideoRunRequest("Lamp", "A desk lamp", "Buy now", " ", null,
                List.of(upload, upload), new ProductVideoRunRequest.VideoConfig("9:16"), null);

        assertThat(englishErrors(request))
                .containsEntry("distinctUploads", "uploadIds must not contain duplicates")
                .containsEntry("noBlankOptionalClaims", "price and promotion must be omitted or contain text");
    }

    /** Binds under the object name Spring MVC gives a {@code @RequestBody} of the same type. */
    private static Map<String, String> englishErrors(Object request) {
        BeanPropertyBindingResult result = new BeanPropertyBindingResult(request, Conventions.getVariableName(request));
        VALIDATOR.validate(request, result);
        return result.getFieldErrors().stream().collect(toMap(FieldError::getField, EnglishBundle::english));
    }
}
