package com.flowcortex.shared.web;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Minimal Spring Boot bootstrap so {@code @WebMvcTest} in this library module
 * has a {@code @SpringBootConfiguration} to build its sliced context from —
 * :shared:web ships no executable application of its own.
 */
@SpringBootApplication
class TestWebApplication {
}
