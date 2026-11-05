package com.flowcortex.llm.api;

/**
 * Asks the model for one JSON answer. Sends exactly what the request holds and never throws for a
 * provider problem: a timeout, an outage or a rejected call comes back as {@link LlmJsonResult.Failed}.
 */
public interface LlmClient {

    LlmJsonResult generateJson(LlmJsonRequest request);
}
