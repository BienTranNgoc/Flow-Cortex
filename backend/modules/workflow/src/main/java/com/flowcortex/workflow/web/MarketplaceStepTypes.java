package com.flowcortex.workflow.web;

import com.flowcortex.workflow.application.MarketplaceQuery;
import com.flowcortex.workflow.application.MarketplaceStepTypeCatalogue;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;

/** At most 10 distinct values, each a step type the engine knows; {@code null} is valid. */
@Documented
@Constraint(validatedBy = MarketplaceStepTypes.Validator.class)
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@interface MarketplaceStepTypes {

    String message() default "must be at most 10 distinct step types the engine knows";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    /** Built by Spring's constraint-validator factory, which hands it the catalogue bean. */
    final class Validator implements ConstraintValidator<MarketplaceStepTypes, List<String>> {

        private final MarketplaceStepTypeCatalogue catalogue;

        Validator(MarketplaceStepTypeCatalogue catalogue) {
            this.catalogue = catalogue;
        }

        @Override
        public boolean isValid(List<String> stepTypes, ConstraintValidatorContext context) {
            return MarketplaceQuery.stepTypesProblem(stepTypes, catalogue.types()).isEmpty();
        }
    }
}
