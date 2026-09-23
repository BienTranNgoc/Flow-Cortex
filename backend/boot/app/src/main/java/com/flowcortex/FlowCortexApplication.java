package com.flowcortex;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.modulith.Modulithic;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Flow-Cortex application entry point.
 *
 * <p>Single Spring Boot bootstrap for the modular monolith; all modules and shared libraries
 * are wired in so Spring Modulith can detect and verify the module structure.
 *
 * <p>{@code @EnableAsync} makes {@code :modules:workflow}'s {@code @ApplicationModuleListener}
 * run on a separate thread after commit — without it, Modulith runs the listener on the
 * publishing thread, defeating the non-blocking async model.
 */
@Modulithic(systemName = "FlowCortex")
@EnableAsync
@SpringBootApplication
public class FlowCortexApplication {

    public static void main(String[] args) {
        SpringApplication.run(FlowCortexApplication.class, args);
    }
}
