package com.flowcortex.support.assistant;

import com.flowcortex.llm.api.LlmClient;
import com.flowcortex.llm.api.LlmJsonRequest;
import com.flowcortex.llm.api.LlmJsonResult;
import io.micrometer.context.ContextExecutorService;
import io.micrometer.context.ContextSnapshotFactory;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Bounds one provider call by a total deadline. The client's read timeout applies per socket read,
 * so a provider trickling bytes could otherwise hold the request far longer; at the deadline the call
 * is interrupted (which closes a virtual thread's socket) and reported as a timeout. The call thread
 * carries the caller's context (observation, trace scope), so the client's span stays in the request.
 */
@Component
class LlmDeadline implements AutoCloseable {

    static final Duration TOTAL = Duration.ofSeconds(20);

    private static final Logger LOG = LoggerFactory.getLogger(LlmDeadline.class);

    private final LlmClient client;
    private final Duration deadline;
    private final ExecutorService executor = ContextExecutorService.wrap(
            Executors.newVirtualThreadPerTaskExecutor(), ContextSnapshotFactory.builder().build());

    @Autowired
    LlmDeadline(LlmClient client) {
        this(client, TOTAL);
    }

    LlmDeadline(LlmClient client, Duration deadline) {
        this.client = client;
        this.deadline = deadline;
    }

    LlmJsonResult call(LlmJsonRequest request) {
        Future<LlmJsonResult> call = executor.submit(() -> client.generateJson(request));
        try {
            return call.get(deadline.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            call.cancel(true);
            LOG.warn("support.assistant.llm outcome=deadline_exceeded deadline_ms={}", deadline.toMillis());
            return new LlmJsonResult.Failed(LlmJsonResult.Kind.TIMEOUT);
        } catch (InterruptedException e) {
            call.cancel(true);
            Thread.currentThread().interrupt();
            return new LlmJsonResult.Failed(LlmJsonResult.Kind.TIMEOUT);
        } catch (ExecutionException e) {
            LOG.error("support.assistant.llm outcome=client_error error={}", e.getCause().getClass().getName());
            return new LlmJsonResult.Failed(LlmJsonResult.Kind.UPSTREAM);
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
