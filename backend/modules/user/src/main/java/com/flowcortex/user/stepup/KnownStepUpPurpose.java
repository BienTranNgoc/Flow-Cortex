package com.flowcortex.user.stepup;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The string names a {@link com.flowcortex.user.api.StepUpPurpose}; {@code null} is valid. Declared
 * on a string so an unknown name is a field error answered as {@code validation-failed}, which a
 * body that fails to deserialize into the enum would not be.
 */
@Documented
@Constraint(validatedBy = KnownStepUpPurposeValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface KnownStepUpPurpose {

    String message() default "must be a known step-up purpose";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
