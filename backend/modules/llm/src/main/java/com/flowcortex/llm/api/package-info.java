/**
 * Public API of the LLM module: the {@link com.flowcortex.llm.api.LlmClient} port for one named model
 * and the {@link com.flowcortex.llm.api.LlmSlots} port for configured model chains, with their request
 * ({@link com.flowcortex.llm.api.LlmSlotRequest} for a slot's JSON call) and result types;
 * {@link com.flowcortex.llm.api.ModelDescriptors} serves the code-defined model fact
 * sheets and {@link com.flowcortex.llm.api.CapabilityDeclarations} takes the capabilities a module runs.
 */
@org.springframework.modulith.NamedInterface("api")
package com.flowcortex.llm.api;
