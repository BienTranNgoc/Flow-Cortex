package com.flowcortex.support.assistant;

import com.flowcortex.llm.api.LlmSlots;
import com.flowcortex.llm.api.LlmTimeouts;
import java.time.Duration;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The slot the assistant calls. Its chain must finish inside the answer timeout, or the deadline would
 * cut a fallback model off before it could answer.
 */
final class AssistantSlot {

    static final String NAME = "support-assistant";

    private AssistantSlot() {
    }

    static void check(Duration worstCase, Duration answerTimeout) {
        if (worstCase.compareTo(answerTimeout) > 0) {
            throw new IllegalStateException("Slot '" + NAME + "' can take " + worstCase
                    + " but the answer timeout is " + answerTimeout
                    + "; shorten its call-timeout or list fewer models");
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class Check {

        /** Stops boot when the slot is missing (the slots port throws) or its chain outlasts the answer timeout. */
        @Bean
        InitializingBean assistantSlotFits(LlmSlots llm, LlmTimeouts timeouts) {
            return () -> AssistantSlot.check(llm.jsonWorstCase(NAME), timeouts.answer());
        }
    }
}
