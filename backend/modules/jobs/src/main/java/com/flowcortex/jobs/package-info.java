/**
 * Jobs module: the cluster job runner that lets one instance at a time run each periodic job, the
 * durable schedule it keeps, and the one resubmitter of failed event publications.
 * <p>
 * This is a Spring Modulith application module. It depends on no other module; the modules that own
 * a job or an event implement the {@code api} port.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Jobs",
        allowedDependencies = {}
)
package com.flowcortex.jobs;
