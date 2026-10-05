package com.flowcortex.workflow.application;

import com.flowcortex.workflow.api.NewWorkflowStep;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.hibernate.validator.constraintvalidation.HibernateConstraintValidatorContext;

/**
 * Every step of a new workflow names a type the engine can run, and a remote type only as the
 * first step — its input is the caller's uploaded file, which only the first step receives.
 * Violations are reported per step as {@code steps[i].type}, the path the shared 400 handler
 * puts under {@code errors}.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = KnownStepTypes.Validator.class)
@interface KnownStepTypes {

    String APPROVAL_CHECKPOINT = "APPROVAL_CHECKPOINT";

    String message() default "unknown step type";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    /** The steps being created, with the types the registry knows and those of them that run remotely. */
    @KnownStepTypes
    record Definition(List<NewWorkflowStep> steps, Set<String> knownTypes, Set<String> remoteTypes) {
    }

    final class Validator implements ConstraintValidator<KnownStepTypes, Definition> {

        @Override
        public boolean isValid(Definition definition, ConstraintValidatorContext context) {
            boolean valid = true;
            // The caller's type goes in as a message parameter, which is inserted literally rather
            // than interpolated; the template itself holds only the registry's own type names.
            Set<String> allowedTypes = new TreeSet<>(definition.knownTypes());
            allowedTypes.add(APPROVAL_CHECKPOINT);
            String template = "unknown capability '{type}'; allowed: " + String.join(", ", allowedTypes);
            HibernateConstraintValidatorContext hibernateContext =
                    context.unwrap(HibernateConstraintValidatorContext.class);
            context.disableDefaultConstraintViolation();

            List<NewWorkflowStep> steps = definition.steps();

            boolean startsRemote = !steps.isEmpty() && definition.remoteTypes().contains(steps.get(0).type());

            for (int i = 0; i < steps.size(); i++) {
                String type = steps.get(i).type();
                if (!allowedTypes.contains(type)) {
                    valid = false;
                    violation(hibernateContext, template, type, i);
                } else if (definition.remoteTypes().contains(type) && !startsRemote) {
                    valid = false;
                    violation(hibernateContext, "'{type}' needs an uploaded file, so the workflow must start with a media step", type, i);
                }
            }
            return valid;
        }

        private static void violation(HibernateConstraintValidatorContext context, String template, String type,
                int index) {
            context.addMessageParameter("type", type)
                    .buildConstraintViolationWithTemplate(template)
                    .addPropertyNode("steps")
                    .addPropertyNode("type").inIterable().atIndex(index)
                    .addConstraintViolation();
        }
    }
}
