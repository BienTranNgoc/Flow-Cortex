package com.flowcortex;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.modulith.Modulithic;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Flow-Cortex application entry point.
 *
 * <p>This is the single Spring Boot bootstrap class for the whole modular
 * monolith. All business modules and shared libraries are wired in as
 * dependencies so Spring Modulith can detect and verify the module structure
 * rooted at this package ({@code com.flowcortex}).
 *
 * <p>{@code @EnableAsync} (design.md D5/R-050, task 5.4) is what makes
 * {@code :modules:workflow}'s {@code @ApplicationModuleListener} actually run
 * on a separate thread after its triggering transaction commits — without
 * it, Spring Modulith silently runs the listener on the publishing (request)
 * thread instead, defeating the non-blocking start the whole async
 * after-commit model exists for.
 */
@Modulithic(systemName = "FlowCortex")
@EnableAsync
@SpringBootApplication
public class FlowCortexApplication {

    public static void main(String[] args) {
        SpringApplication.run(FlowCortexApplication.class, args);
    }
}
