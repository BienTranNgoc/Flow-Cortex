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
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.hibernate.validator.constraintvalidation.HibernateConstraintValidatorContext;

/**
 * Every step of a new workflow names a type the engine can run, and a remote type only in a
 * workflow whose first step is remote — the run then starts from the caller's uploaded file.
 * A step's declared job inputs ({@link DeclaredInputs}) are well-formed, read only earlier steps,
 * and are declared only by a remote step. Violations are reported per step as {@code steps[i].type} or {@code steps[i].config},
 * the paths the shared 400 handler puts under {@code errors}.
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
                boolean runsInline = allowedTypes.contains(type) && !definition.remoteTypes().contains(type);
                String inputsFault = inputsFault(steps.get(i), i + 1, runsInline);
                if (inputsFault != null) {
                    valid = false;
                    // A message parameter is inserted literally, so the caller's text is never interpolated.
                    hibernateContext.addMessageParameter("fault", inputsFault)
                            .buildConstraintViolationWithTemplate("{fault}")
                            .addPropertyNode("steps")
                            .addPropertyNode("config").inIterable().atIndex(i)
                            .addConstraintViolation();
                }
            }
            return valid;
        }

        /** What is wrong with the inputs step {@code order} declares in its config, or {@code null}. */
        private static String inputsFault(NewWorkflowStep step, int order, boolean runsInline) {
            try {
                Map<String, DeclaredInputs.Source> inputs = DeclaredInputs.parse(step.config());
                if (runsInline && !inputs.isEmpty()) {
                    return "only a remote step reads declared inputs; '" + step.type() + "' runs inline";
                }
                for (Map.Entry<String, DeclaredInputs.Source> input : inputs.entrySet()) {
                    int source = input.getValue().stepOrder();
                    if (!input.getValue().isUpload() && source >= order) {
                        return "input '" + input.getKey() + "' reads step " + source
                                + ", which does not run before step " + order;
                    }
                }
                return null;
            } catch (IllegalArgumentException e) {
                return e.getMessage();
            }
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
