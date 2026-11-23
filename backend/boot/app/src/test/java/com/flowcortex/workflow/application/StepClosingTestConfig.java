package com.flowcortex.workflow.application;

import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Lets a test close a running step through the engine's own step transaction, as an executor's result
 * would, and run one reaper pass on demand. It lives in the engine's package because the transaction
 * and reaper beans are package-private.
 */
@TestConfiguration(proxyBeanMethods = false)
public class StepClosingTestConfig {

    @Bean
    StepCloser stepCloser(RunStepTransactions transactions, LeaseReaper reaper) {
        return new StepCloser(transactions, reaper);
    }

    public static final class StepCloser {

        private final RunStepTransactions transactions;
        private final LeaseReaper reaper;

        StepCloser(RunStepTransactions transactions, LeaseReaper reaper) {
            this.transactions = transactions;
            this.reaper = reaper;
        }

        /** Fails a running step that is on its first attempt; false when it is no longer running. */
        public boolean fail(UUID runId, UUID stepId, String code, String message) {
            return transactions.fail(runId, stepId, (short) 1, code, message, Instant.now());
        }

        /** Completes a running step that is on its first attempt, without a stored output; false when it is no longer running. */
        public boolean complete(UUID runId, UUID stepId, String output) {
            return transactions.complete(runId, stepId, (short) 1, output, null, Instant.now());
        }

        /** One pass of the reaper: closes the steps past a bound, then advances the runs nothing is moving. */
        public void reap() {
            reaper.reap();
        }
    }
}
