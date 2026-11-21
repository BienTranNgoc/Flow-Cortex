package com.flowcortex.app.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.endpoint.EndpointId;
import org.springframework.boot.actuate.endpoint.web.PathMappedEndpoints;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Says once at startup whether Prometheus can scrape this backend: a Prometheus registry is
 * registered and the {@code prometheus} endpoint is exposed on the web. A missing piece is logged
 * at ERROR and never stops startup. Nothing is counted, because a counter about the pipeline would
 * travel through the pipeline it reports absent.
 */
@Component
class MetricsPipelineAssertion {

    private static final Logger LOG = LoggerFactory.getLogger(MetricsPipelineAssertion.class);
    private static final EndpointId PROMETHEUS = EndpointId.of("prometheus");

    /** The pipeline's state; {@code present} holds when both pieces are there. */
    record State(boolean prometheusRegistry, boolean endpointExposed) {

        boolean present() {
            return prometheusRegistry && endpointExposed;
        }
    }

    private final MeterRegistry meterRegistry;
    private final PathMappedEndpoints endpoints;

    MetricsPipelineAssertion(MeterRegistry meterRegistry, PathMappedEndpoints endpoints) {
        this.meterRegistry = meterRegistry;
        this.endpoints = endpoints;
    }

    static State describe(MeterRegistry registry, boolean endpointExposed) {
        return new State(hasPrometheusRegistry(registry), endpointExposed);
    }

    private static boolean hasPrometheusRegistry(MeterRegistry registry) {
        if (registry instanceof PrometheusMeterRegistry) {
            return true;
        }
        if (registry instanceof CompositeMeterRegistry composite) {
            return composite.getRegistries().stream().anyMatch(MetricsPipelineAssertion::hasPrometheusRegistry);
        }
        return false;
    }

    /** Applies Boot's include and exclude rules, so {@code *} and {@code exclude} are judged as served. */
    boolean endpointExposed() {
        return endpoints.getEndpoint(PROMETHEUS) != null;
    }

    @EventListener(ApplicationReadyEvent.class)
    void onReady() {
        try {
            log(describe(meterRegistry, endpointExposed()));
        } catch (RuntimeException e) {
            LOG.error("observability.metrics_pipeline metrics_pipeline=unknown remedy=\"the check itself failed\"", e);
        }
    }

    static void log(State state) {
        try {
            if (state.present()) {
                LOG.info("observability.metrics_pipeline metrics_pipeline=present");
                return;
            }
            LOG.error("observability.metrics_pipeline metrics_pipeline=absent prometheus_registry={} "
                    + "prometheus_endpoint_exposed={} remedy=\"{}\"",
                    state.prometheusRegistry(), state.endpointExposed(), remedy(state));
        } catch (RuntimeException e) {
            // The statement must never stop startup; the logger failing leaves nothing else to report through.
        }
    }

    private static String remedy(State state) {
        if (!state.prometheusRegistry() && !state.endpointExposed()) {
            return "add micrometer-registry-prometheus and expose the prometheus endpoint "
                    + "(management.endpoints.web.exposure.include)";
        }
        if (!state.prometheusRegistry()) {
            return "add micrometer-registry-prometheus so a Prometheus meter registry is registered";
        }
        return "expose the prometheus endpoint (management.endpoints.web.exposure.include must list it "
                + "and exclude must not)";
    }
}
