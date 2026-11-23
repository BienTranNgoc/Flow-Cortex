package com.flowcortex.onboarding.web;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** A sample body that names a workflow also carries a non-blank input and a source language. */
@Documented
@Constraint(validatedBy = SampleFieldsPresentValidator.class)
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface SampleFieldsPresent {

    String message() default "{onboarding.sample.inputRequired}";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
