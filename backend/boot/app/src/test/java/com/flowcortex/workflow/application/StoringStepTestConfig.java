package com.flowcortex.workflow.application;

import com.flowcortex.media.api.MediaJobs;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Every step type of the production registry plus {@value #TYPE}, an inline step that stores one
 * object for its own step through the media port, as an inline step that writes files does. It
 * lives in the engine's package because executors and the registry are package-private.
 */
@TestConfiguration(proxyBeanMethods = false)
public class StoringStepTestConfig {

    public static final String TYPE = "store_voice";
    public static final String OBJECT_NAME = "voice.wav";

    @Bean
    @Primary
    StepExecutorRegistry registryWithAStoringStep(@Qualifier("stepExecutorRegistry") StepExecutorRegistry production,
            MediaJobs mediaJobs) {
        List<StepExecutor> executors = new ArrayList<>();
        production.knownTypes().forEach(type -> executors.add(production.find(type).orElseThrow()));
        executors.add(new StoringStepExecutor(mediaJobs));
        return new StepExecutorRegistry(executors);
    }

    private static final class StoringStepExecutor implements StepExecutor {

        private final MediaJobs mediaJobs;

        StoringStepExecutor(MediaJobs mediaJobs) {
            this.mediaJobs = mediaJobs;
        }

        @Override
        public String type() {
            return TYPE;
        }

        @Override
        public Mode mode() {
            return Mode.INLINE;
        }

        @Override
        public Duration maxRuntime() {
            return Duration.ofSeconds(60);
        }

        @Override
        public String execute(StepContext context) {
            context.store(mediaJobs, OBJECT_NAME, "audio/wav", "RIFF".getBytes(StandardCharsets.US_ASCII));
            return "{\"voiced\":1}";
        }
    }
}
