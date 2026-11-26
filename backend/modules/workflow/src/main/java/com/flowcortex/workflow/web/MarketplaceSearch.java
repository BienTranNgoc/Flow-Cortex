package com.flowcortex.workflow.web;

import com.flowcortex.workflow.application.MarketplaceQuery;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** The search text is at most 100 characters and 8 words; {@code null} is valid. */
@Documented
@Constraint(validatedBy = MarketplaceSearch.Validator.class)
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@interface MarketplaceSearch {

    String message() default "must be at most 100 characters and 8 words";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    final class Validator implements ConstraintValidator<MarketplaceSearch, String> {

        @Override
        public boolean isValid(String q, ConstraintValidatorContext context) {
            return MarketplaceQuery.searchProblem(q).isEmpty();
        }
    }
}
