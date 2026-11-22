package com.flowcortex.workflow.application;

import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Lets a test close a running step through the engine's own step transaction, as an executor's result
 * would. It lives in the engine's package because the transaction bean is package-private.
 */
@TestConfiguration(proxyBeanMethods = false)
public class StepClosingTestConfig {

    @Bean
    StepCloser stepCloser(RunStepTransactions transactions) {
        return new StepCloser(transactions);
    }

    public static final class StepCloser {

        private final RunStepTransactions transactions;

        StepCloser(RunStepTransactions transactions) {
            this.transactions = transactions;
        }

        /** Fails a running step that is on its first attempt; false when it is no longer running. */
        public boolean fail(UUID runId, UUID stepId, String code, String message) {
            return transactions.fail(runId, stepId, (short) 1, code, message, Instant.now());
        }
    }
}
