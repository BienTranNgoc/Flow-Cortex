/**
 * LLM module: calls to a hosted language model, behind one provider-neutral port.
 * <p>
 * This is a Spring Modulith application module. Other modules reach it only through its public
 * {@code api} port; it depends on no other module.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "LLM",
        allowedDependencies = {}
)
package com.flowcortex.llm;
