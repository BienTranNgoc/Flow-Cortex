package com.flowcortex.app.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.endpoint.EndpointAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.export.prometheus.PrometheusMetricsExportAutoConfiguration;
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.slf4j.LoggerFactory;

/**
 * The startup statement says whether Prometheus can scrape the backend: a Prometheus registry is
 * registered and the {@code prometheus} endpoint is exposed on the web.
 */
class MetricsPipelineAssertionTest {

    private final Logger log = (Logger) LoggerFactory.getLogger(MetricsPipelineAssertion.class);
    private final ListAppender<ILoggingEvent> lines = new ListAppender<>();

    @BeforeEach
    void captureLog() {
        lines.start();
        log.addAppender(lines);
    }

    @AfterEach
    void releaseLog() {
        log.detachAppender(lines);
    }

    private static PrometheusMeterRegistry prometheus() {
        return new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    }

    @Test
    void aPrometheusRegistryWithTheEndpointExposedIsPresent() {
        MetricsPipelineAssertion.State state = MetricsPipelineAssertion.describe(prometheus(), true);

        assertThat(state.present()).isTrue();
        assertThat(state.prometheusRegistry()).isTrue();
        assertThat(state.endpointExposed()).isTrue();
    }

    @Test
    void aPrometheusRegistryInsideACompositeIsPresent() {
        CompositeMeterRegistry composite = new CompositeMeterRegistry();
        composite.add(new SimpleMeterRegistry());
        composite.add(prometheus());

        assertThat(MetricsPipelineAssertion.describe(composite, true).present()).isTrue();
    }

    @Test
    void anEndpointThatIsNotExposedIsAbsent() {
        MetricsPipelineAssertion.State state = MetricsPipelineAssertion.describe(prometheus(), false);

        assertThat(state.present()).isFalse();
        assertThat(state.prometheusRegistry()).isTrue();
        assertThat(state.endpointExposed()).isFalse();
    }

    @Test
    void aSimpleRegistryOnlyIsAbsent() {
        MetricsPipelineAssertion.State state = MetricsPipelineAssertion.describe(new SimpleMeterRegistry(), true);

        assertThat(state.present()).isFalse();
        assertThat(state.prometheusRegistry()).isFalse();
    }

    @Test
    void presentIsOneInfoLineWithoutHostOrPort() {
        MetricsPipelineAssertion.log(MetricsPipelineAssertion.describe(prometheus(), true));

        assertThat(lines.list).hasSize(1);
        ILoggingEvent line = lines.list.get(0);
        assertThat(line.getLevel()).isEqualTo(Level.INFO);
        assertThat(line.getFormattedMessage())
                .contains("observability.metrics_pipeline", "metrics_pipeline=present")
                .doesNotContain("http")
                .doesNotContain("8081");
    }

    @Test
    void absentIsOneErrorLineNamingTheMissingPiece() {
        MetricsPipelineAssertion.log(MetricsPipelineAssertion.describe(prometheus(), false));

        assertThat(lines.list).hasSize(1);
        ILoggingEvent line = lines.list.get(0);
        assertThat(line.getLevel()).isEqualTo(Level.ERROR);
        assertThat(line.getFormattedMessage())
                .contains("observability.metrics_pipeline", "metrics_pipeline=absent",
                        "prometheus_registry=true", "prometheus_endpoint_exposed=false", "remedy=")
                .doesNotContain("http");
    }

    @Test
    void aMissingRegistryIsNamedInTheRemedy() {
        MetricsPipelineAssertion.log(MetricsPipelineAssertion.describe(new SimpleMeterRegistry(), true));

        assertThat(lines.list.get(0).getFormattedMessage())
                .contains("prometheus_registry=false", "micrometer-registry-prometheus");
    }

    @Test
    void loggingNeverThrows() {
        assertThatCode(() -> MetricsPipelineAssertion.log(null)).doesNotThrowAnyException();
    }

    // Exposure is resolved by Spring Boot's own include/exclude handling, not by reading the property.

    private WebApplicationContextRunner runner(String... properties) {
        return new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        EndpointAutoConfiguration.class,
                        WebEndpointAutoConfiguration.class,
                        MetricsAutoConfiguration.class,
                        CompositeMeterRegistryAutoConfiguration.class,
                        PrometheusMetricsExportAutoConfiguration.class))
                .withBean(MetricsPipelineAssertion.class)
                .withPropertyValues(properties);
    }

    private static boolean exposed(AssertableWebApplicationContext context) {
        assertThat(context).hasNotFailed();
        return context.getBean(MetricsPipelineAssertion.class).endpointExposed();
    }

    @Test
    void anExplicitIncludeExposesTheEndpoint() {
        runner("management.endpoints.web.exposure.include=health,info,prometheus")
                .run(context -> assertThat(exposed(context)).isTrue());
    }

    @Test
    void aListWithoutPrometheusDoesNotExposeIt() {
        runner("management.endpoints.web.exposure.include=health,info")
                .run(context -> assertThat(exposed(context)).isFalse());
    }

    @Test
    void aWildcardExposesTheEndpoint() {
        runner("management.endpoints.web.exposure.include=*")
                .run(context -> assertThat(exposed(context)).isTrue());
    }

    @Test
    void aWildcardWithAnExclusionDoesNotExposeIt() {
        runner("management.endpoints.web.exposure.include=*", "management.endpoints.web.exposure.exclude=prometheus")
                .run(context -> assertThat(exposed(context)).isFalse());
    }

    @Test
    void theListenerLogsThePipelineStateOnceWhenTheApplicationIsReady() {
        runner("management.endpoints.web.exposure.include=health,info")
                .run(context -> {
                    context.getBean(MetricsPipelineAssertion.class).onReady();

                    assertThat(lines.list).hasSize(1);
                    assertThat(lines.list.get(0).getLevel()).isEqualTo(Level.ERROR);
                    assertThat(lines.list.get(0).getFormattedMessage())
                            .contains("prometheus_registry=true", "prometheus_endpoint_exposed=false");
                });
    }
}
