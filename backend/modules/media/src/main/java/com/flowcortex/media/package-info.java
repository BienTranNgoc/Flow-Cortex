/**
 * Media module: the media engine's side of the API — the job contract and its validation, the
 * broker topology, jobs out through the outbox, results and progress in through listeners, and
 * the object store (uploads signed for the member's browser).
 * <p>
 * This is a Spring Modulith application module. Other modules reach it only through its public
 * {@code api} port.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Media",
        allowedDependencies = {
                "shared :: web"
        }
)
package com.flowcortex.media;
