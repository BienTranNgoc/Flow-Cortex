package com.flowcortex.shared.web.audit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an admin write route for the audit log.
 *
 * <p>{@code value} is the action, {@code <resource_type>.<verb>}. {@code resourceParam} names the path
 * variable that identifies the resource; without such a variable the id is the response field of that
 * name, else the response {@code id}. An empty {@code resourceParam} takes the id from the response
 * alone, for a create route nested under a parent path variable. Only the request-body fields in {@code capture} and the path
 * variables in {@code params} reach the change summary.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AdminAudit {

    String value();

    String resourceParam() default "id";

    String[] capture() default {};

    String[] params() default {};
}
