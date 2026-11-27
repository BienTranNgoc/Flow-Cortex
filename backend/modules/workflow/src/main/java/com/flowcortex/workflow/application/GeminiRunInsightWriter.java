package com.flowcortex.workflow.application;

import com.flowcortex.llm.api.LlmJsonResult;
import com.flowcortex.llm.api.LlmSlots;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Asks Gemini for the insight of a table result. A bean only while {@code flowcortex.workflow.run-insight.enabled} is
 * true: on, a bounded sample of the table leaves for the provider. The caller logs and counts what the call came to.
 */
@Component
@ConditionalOnProperty(name = "flowcortex.workflow.run-insight.enabled", havingValue = "true")
class GeminiRunInsightWriter {

    static final String SLOT = "run-insight";

    private final LlmSlots llm;

    /** Stops boot, naming the slot, when it is not configured under {@code flowcortex.llm.gemini.slots}. */
    GeminiRunInsightWriter(LlmSlots llm) {
        llm.jsonWorstCase(SLOT);
        this.llm = llm;
    }

    LlmJsonResult answer(String prompt) {
        return llm.generateJson(SLOT, prompt, InsightAnswer.SCHEMA);
    }
}
