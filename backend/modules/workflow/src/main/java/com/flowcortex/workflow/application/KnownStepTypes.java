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
 * Every step of a new workflow names a type the engine can run. A type that consumes assets
 * snapshotted at run start is valid only when the workflow starts with that input model;
 * provider-backed remote steps resolve ordinary run/prior-step data and do not share this rule.
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

    /**
     * The steps being created, with the types the registry knows, those of them that take the
     * assets stored at run start, and those of them that run remotely.
     */
    @KnownStepTypes
    record Definition(List<NewWorkflowStep> steps, Set<String> knownTypes, Set<String> storedInputTypes,
            Set<String> remoteTypes) {
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

            boolean startsWithStoredInput = !steps.isEmpty()
                    && definition.storedInputTypes().contains(steps.get(0).type());

            for (int i = 0; i < steps.size(); i++) {
                String type = steps.get(i).type();
                if (!allowedTypes.contains(type)) {
                    valid = false;
                    violation(hibernateContext, template, type, i);
                } else if (definition.storedInputTypes().contains(type) && !startsWithStoredInput) {
                    valid = false;
                    violation(hibernateContext, "'{type}' needs an uploaded file, so the workflow must start with a media step", type, i);
                }
                boolean runsInline = allowedTypes.contains(type) && !definition.remoteTypes().contains(type);
                String configFault = configFault(steps.get(i), i + 1, runsInline);
                if (configFault != null) {
                    valid = false;
                    // A message parameter is inserted literally, so the caller's text is never interpolated.
                    hibernateContext.addMessageParameter("fault", configFault)
                            .buildConstraintViolationWithTemplate("{fault}")
                            .addPropertyNode("steps")
                            .addPropertyNode("config").inIterable().atIndex(i)
                            .addConstraintViolation();
                }
            }
            return valid;
        }

        /** What is wrong with the {@code outputInputs} step {@code order} declares in its config, or {@code null}. */
        private static String configFault(NewWorkflowStep step, int order, boolean runsInline) {
            String fault = StoredWithOutputsResolver.declarationFault(step.config(), order);
            if (fault == null && runsInline && StoredWithOutputsResolver.declaresOutputInputs(step.config())) {
                return "only a remote step reads outputInputs; '" + step.type() + "' runs inline";
            }
            return fault;
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
