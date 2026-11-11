package com.flowcortex.onboarding.web;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Attaches each violation to its own field so the 400 {@code errors} map names {@code sampleInput}
 * and {@code sourceLocale}; a method-level assertion would be keyed by its getter name instead.
 */
public class SampleFieldsPresentValidator
        implements ConstraintValidator<SampleFieldsPresent, AdminOnboardingSampleUpdate> {

    private static final String LOCALE_MESSAGE = "{onboarding.field.notNull}";

    @Override
    public boolean isValid(AdminOnboardingSampleUpdate body, ConstraintValidatorContext context) {
        if (body == null || body.workflowId() == null) {
            return true;
        }
        boolean valid = true;
        context.disableDefaultConstraintViolation();
        if (body.sampleInput() == null || body.sampleInput().isBlank()) {
            context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
                    .addPropertyNode("sampleInput").addConstraintViolation();
            valid = false;
        }
        if (body.sourceLocale() == null) {
            context.buildConstraintViolationWithTemplate(LOCALE_MESSAGE)
                    .addPropertyNode("sourceLocale").addConstraintViolation();
            valid = false;
        }
        return valid;
    }
}
