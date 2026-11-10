package com.flowcortex.jobs.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowcortex.jobs.api.ClusterJob;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The startup checks on job beans, and the order jobs run in. */
class ClusterJobRegistryTest {

    private static ClusterJob job(String name, Duration interval) {
        return new ClusterJob() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Duration interval() {
                return interval;
            }

            @Override
            public void run() {
            }
        };
    }

    @Test
    void twoJobsWithOneNameRefuseToStartAndTheMessageNamesIt() {
        List<ClusterJob> jobs = List.of(job("support.request-auto-close", Duration.ofHours(1)),
                job("support.request-auto-close", Duration.ofMinutes(5)));

        assertThatThrownBy(() -> new ClusterJobRegistry(jobs))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("support.request-auto-close");
    }

    @Test
    void aNameOutsideThePatternRefusesToStartAndTheMessageNamesItAndThePattern() {
        List<ClusterJob> jobs = List.of(job("Auth Retention", Duration.ofHours(1)));

        assertThatThrownBy(() -> new ClusterJobRegistry(jobs))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Auth Retention").hasMessageContaining("[a-z0-9.-]{3,64}");
    }

    @Test
    void aNameOfTwoOrSixtyFiveCharactersIsRefused() {
        assertThatThrownBy(() -> new ClusterJobRegistry(List.of(job("ab", Duration.ofHours(1)))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new ClusterJobRegistry(List.of(job("a".repeat(65), Duration.ofHours(1)))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aZeroOrNegativeIntervalRefusesToStartAndTheMessageNamesTheJob() {
        assertThatThrownBy(() -> new ClusterJobRegistry(List.of(job("user.phone-retention", Duration.ZERO))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("user.phone-retention");
        assertThatThrownBy(() -> new ClusterJobRegistry(
                List.of(job("user.phone-retention", Duration.ofSeconds(-1)))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("user.phone-retention");
    }

    @Test
    void jobsComeBackByIntervalThenName() {
        ClusterJob hourlyB = job("user.b-job", Duration.ofHours(1));
        ClusterJob hourlyA = job("user.a-job", Duration.ofHours(1));
        ClusterJob minute = job("jobs.minute", Duration.ofSeconds(60));
        ClusterJob half = job("workflow.half", Duration.ofSeconds(30));

        ClusterJobRegistry registry = new ClusterJobRegistry(List.of(hourlyB, minute, hourlyA, half));

        assertThat(registry.jobs()).containsExactly(half, minute, hourlyA, hourlyB);
    }
}
