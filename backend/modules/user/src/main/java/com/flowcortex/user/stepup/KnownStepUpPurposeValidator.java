package com.flowcortex.user.stepup;

import com.flowcortex.user.api.StepUpPurpose;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.Arrays;

class KnownStepUpPurposeValidator implements ConstraintValidator<KnownStepUpPurpose, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return value == null || Arrays.stream(StepUpPurpose.values()).anyMatch(p -> p.name().equals(value));
    }
}
