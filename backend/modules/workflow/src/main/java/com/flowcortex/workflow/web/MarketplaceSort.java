package com.flowcortex.workflow.web;

import com.flowcortex.workflow.application.MarketplaceQuery.Sort;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** The value is the wire name of one of the catalogue's orders; {@code null} is valid and means trending. */
@Documented
@Constraint(validatedBy = MarketplaceSort.Validator.class)
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@interface MarketplaceSort {

    String message() default "must be one of trending, popular, newest, topRated, name";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    final class Validator implements ConstraintValidator<MarketplaceSort, String> {

        @Override
        public boolean isValid(String sort, ConstraintValidatorContext context) {
            return Sort.parse(sort).isPresent();
        }
    }
}
