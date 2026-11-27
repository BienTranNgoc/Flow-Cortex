package com.flowcortex.workflow.web;

import com.flowcortex.workflow.api.schedule.ScheduleFrequency;
import com.flowcortex.workflow.api.schedule.SetSchedule;
import com.flowcortex.workflow.application.ScheduleClock;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.Locale;

/**
 * Request bodies of the schedule routes. Every rule is a constraint that reports under the field it concerns, so
 * the shared 400 handler keys {@code errors} by {@code frequency}, {@code dayOfWeek}, {@code dayOfMonth},
 * {@code time}, {@code timezone} or {@code input}. The enum-like fields are strings with a pattern: an enum-typed
 * field fails in Jackson, before validation, with a 400 that has no {@code errors} map.
 */
final class ScheduleRequests {

    static final String DEFAULT_TIME_ZONE = "Asia/Ho_Chi_Minh";

    private ScheduleRequests() {
    }

    /**
     * The fields that depend on another field or on the zone database: the day the frequency needs, and a zone
     * {@link ScheduleClock#parseZone} accepts. Violations carry the property they concern.
     */
    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    @Constraint(validatedBy = ValidSchedule.Validator.class)
    @interface ValidSchedule {

        String message() default "invalid schedule";

        Class<?>[] groups() default {};

        Class<? extends Payload>[] payload() default {};

        final class Validator implements ConstraintValidator<ValidSchedule, SetScheduleRequest> {

            @Override
            public boolean isValid(SetScheduleRequest request, ConstraintValidatorContext context) {
                if (request == null) {
                    return true;
                }
                context.disableDefaultConstraintViolation();
                boolean valid = true;
                if (ScheduleFrequency.WEEKLY.wire().equals(request.frequency()) && request.dayOfWeek() == null) {
                    valid = violation(context, "dayOfWeek");
                }
                if (ScheduleFrequency.MONTHLY.wire().equals(request.frequency()) && request.dayOfMonth() == null) {
                    valid = violation(context, "dayOfMonth");
                }
                if (request.timezone() != null && !zoneAccepted(request.timezone())) {
                    valid = violation(context, "timezone");
                }
                return valid;
            }

            private static boolean violation(ConstraintValidatorContext context, String property) {
                context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
                        .addPropertyNode(property)
                        .addConstraintViolation();
                return false;
            }

            private static boolean zoneAccepted(String zone) {
                try {
                    ScheduleClock.parseZone(zone);
                    return true;
                } catch (IllegalArgumentException e) {
                    return false;
                }
            }
        }
    }

    /**
     * {@code timezone} defaults to {@code Asia/Ho_Chi_Minh}, {@code active} to true and {@code acknowledgeCost} to
     * false. A day field the frequency does not use is ignored.
     */
    @ValidSchedule
    record SetScheduleRequest(
            @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"daily", "weekly", "monthly"})
            @NotNull @Pattern(regexp = "daily|weekly|monthly") String frequency,
            @Schema(allowableValues = {"monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"})
            @Pattern(regexp = "monday|tuesday|wednesday|thursday|friday|saturday|sunday") String dayOfWeek,
            @Schema(minimum = "1", maximum = "28") @Min(1) @Max(28) Integer dayOfMonth,
            @Schema(requiredMode = RequiredMode.REQUIRED, example = "08:00",
                    description = "HH:mm on the quarter hour, in the schedule's time zone")
            @NotNull @Pattern(regexp = "([01]\\d|2[0-3]):(00|15|30|45)") String time,
            @Schema(defaultValue = DEFAULT_TIME_ZONE, description = "A region id such as Asia/Ho_Chi_Minh, or UTC")
            String timezone,
            @Schema(defaultValue = "true") Boolean active,
            @Size(max = 10_000) String input,
            @Schema(defaultValue = "false") Boolean acknowledgeCost) {

        SetSchedule toSetSchedule() {
            return new SetSchedule(ScheduleFrequency.fromWire(frequency),
                    dayOfWeek == null ? null : DayOfWeek.valueOf(dayOfWeek.toUpperCase(Locale.ROOT)), dayOfMonth,
                    LocalTime.parse(time), timezone == null ? DEFAULT_TIME_ZONE : timezone,
                    active == null || active, input, Boolean.TRUE.equals(acknowledgeCost));
        }
    }
}
