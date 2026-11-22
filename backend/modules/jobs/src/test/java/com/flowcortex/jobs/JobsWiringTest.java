package com.flowcortex.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.flowcortex.jobs.api.ClusterJob;
import com.flowcortex.jobs.api.PublicationRetryPolicy;
import com.flowcortex.jobs.application.ClusterJobRegistry;
import com.flowcortex.jobs.application.ClusterJobRunner;
import com.flowcortex.jobs.application.PublicationRetryPolicies;
import com.flowcortex.jobs.infrastructure.FailedPublicationResubmitter;
import com.flowcortex.jobs.infrastructure.ScheduledJobRepository;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.modulith.events.FailedEventPublications;
import org.springframework.modulith.events.core.EventPublicationRepository;

/**
 * Starts the jobs module's beans with the circular-reference switch off, as Spring Boot does, so a
 * constructor cycle between them fails here without Docker.
 */
class JobsWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(context -> ((GenericApplicationContext) context).setAllowCircularReferences(false))
            .withBean(FailedEventPublications.class, () -> mock(FailedEventPublications.class))
            .withBean(EventPublicationRepository.class, () -> mock(EventPublicationRepository.class))
            .withBean(ScheduledJobRepository.class, () -> mock(ScheduledJobRepository.class))
            .withBean("probeJob", ClusterJob.class, JobsWiringTest::probeJob)
            .withBean("probePolicy", PublicationRetryPolicy.class, JobsWiringTest::probePolicy)
            .withUserConfiguration(ClusterJobRegistry.class, PublicationRetryPolicies.class,
                    FailedPublicationResubmitter.class, ClusterJobRunner.class);

    @Test
    void theModuleStartsAndTheResubmitterIsAmongTheRegisteredJobs() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ClusterJobRegistry.class).jobs())
                    .extracting(ClusterJob::name)
                    .containsExactly("jobs.publication-resubmitter", "test.probe");
            assertThat(context.getBean(PublicationRetryPolicies.class).policyFor(String.class).maxAttempts())
                    .isEqualTo(3);
        });
    }

    @Test
    void withTheRunnerSwitchedOffTheJobBeansStillStart() {
        runner.withPropertyValues("flowcortex.jobs.runner.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(ClusterJobRunner.class);
            assertThat(context).hasSingleBean(ClusterJobRegistry.class);
        });
    }

    private static ClusterJob probeJob() {
        return new ClusterJob() {
            @Override
            public String name() {
                return "test.probe";
            }

            @Override
            public Duration interval() {
                return Duration.ofHours(1);
            }

            @Override
            public void run() {
            }
        };
    }

    private static PublicationRetryPolicy probePolicy() {
        return new PublicationRetryPolicy() {
            @Override
            public Class<?> eventType() {
                return String.class;
            }

            @Override
            public int maxAttempts() {
                return 3;
            }

            @Override
            public Duration delayAfter(int attempts) {
                return Duration.ofMinutes(1);
            }
        };
    }
}
