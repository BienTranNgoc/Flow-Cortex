/**
 * CV check module: scores a CV against a job description with the hosted language model.
 * <p>
 * This is a Spring Modulith application module. It reaches other modules only through their public
 * {@code api} ports, plus the shared libraries.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "CV check",
        allowedDependencies = {"llm :: api", "jobs :: api", "shared :: web", "shared :: security"}
)
package com.flowcortex.cvcheck;
