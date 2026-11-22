package com.flowcortex.billing.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.flowcortex.billing.momo.MomoCallbackRoute;
import com.flowcortex.billing.momo.MomoCallbackVerifier;
import com.flowcortex.billing.momo.MomoProperties;
import com.flowcortex.billing.provider.PaymentProvider;
import com.flowcortex.billing.provider.PaymentProviders;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.Filter;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(classes = PaymentWebhookControllerWebTest.Wiring.class)
class PaymentWebhookControllerWebTest {

    private static final String ALLOWED = "118.68.171.198";
    private static final String UNLISTED = "203.0.113.9";
    private static final Instant NOW = Instant.parse("2026-11-10T08:00:00Z");

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    @Import(BillingWebhookSecurityConfig.class)
    static class Wiring {

        @Bean
        PaymentProvider momo() {
            PaymentProvider provider = mock(PaymentProvider.class);
            when(provider.key()).thenReturn("momo");
            return provider;
        }

        @Bean
        PaymentProviders providers(PaymentProvider momo) {
            return new PaymentProviders(List.of(momo));
        }

        @Bean
        MomoProperties properties() {
            return new MomoProperties(true, null, "MOMOTEST", "a", "s", null, null, null, "118.68.171.0/24", null);
        }

        @Bean
        PaymentWebhookIngest ingest() {
            return mock(PaymentWebhookIngest.class);
        }

        @Bean
        MeterRegistry meters() {
            return new SimpleMeterRegistry();
        }

        @Bean
        WebhookTelemetry telemetry(MeterRegistry meters) {
            return new WebhookTelemetry(meters);
        }

        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }

        @Bean
        PaymentWebhookController controller(PaymentProviders providers, MomoProperties properties,
                PaymentWebhookIngest ingest, WebhookTelemetry telemetry, Clock clock) {
            return new PaymentWebhookController(providers, properties, ingest, telemetry, clock);
        }
    }

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private PaymentWebhookIngest ingest;

    @Autowired
    private PaymentProvider momo;

    @Autowired
    private MeterRegistry meters;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        reset(ingest);
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean(FilterChainProxy.class))
                .build();
    }

    private static RequestPostProcessor from(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }

    private ResultActions callback(String address, byte[] body) throws Exception {
        return mvc.perform(post(MomoCallbackRoute.PATH).content(body).with(from(address)));
    }

    private double count(String disposition) {
        var counter = meters.find("payment.webhook.callback").tag("disposition", disposition).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void aCallbackFromAnAllowlistedAddressWithoutATokenReachesIngest() throws Exception {
        byte[] body = "{}".getBytes();
        when(ingest.handle(any(), eq(ALLOWED), eq(NOW))).thenReturn(HttpStatus.NO_CONTENT);

        callback(ALLOWED, body).andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(204));

        verify(ingest).handle(body, ALLOWED, NOW);
    }

    @Test
    void ingestAnswersBecomeBareStatuses() throws Exception {
        for (HttpStatus status : List.of(HttpStatus.NO_CONTENT, HttpStatus.UNAUTHORIZED,
                HttpStatus.SERVICE_UNAVAILABLE)) {
            when(ingest.handle(any(), any(), any())).thenReturn(status);

            callback(ALLOWED, "{}".getBytes()).andExpect(r -> {
                assertThat(r.getResponse().getStatus()).isEqualTo(status.value());
                assertThat(r.getResponse().getContentAsByteArray()).isEmpty();
            });
        }
    }

    @Test
    void anAddressOutsideTheAllowlistIsRefusedWith403BeforeTheBodyIsRead() throws Exception {
        AtomicBoolean bodyRead = new AtomicBoolean();
        Filter tracking = (request, response, chain) -> chain.doFilter(
                new HttpServletRequestWrapper((HttpServletRequest) request) {
                    @Override
                    public ServletInputStream getInputStream() throws IOException {
                        bodyRead.set(true);
                        return super.getInputStream();
                    }
                }, response);
        MockMvc watching = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(tracking, context.getBean(FilterChainProxy.class))
                .build();
        double before = count("refused_source");

        watching.perform(post(MomoCallbackRoute.PATH).content("{}").with(from(UNLISTED)))
                .andExpect(r -> {
                    assertThat(r.getResponse().getStatus()).isEqualTo(403);
                    assertThat(r.getResponse().getContentAsByteArray()).isEmpty();
                });

        assertThat(bodyRead).isFalse();
        verifyNoInteractions(ingest);
        assertThat(count("refused_source")).isEqualTo(before + 1);
    }

    @Test
    void aBodyOverTheCeilingIsHandedOverOneByteBeyondIt() throws Exception {
        byte[] huge = new byte[MomoCallbackVerifier.MAX_BODY_BYTES * 2];
        when(ingest.handle(any(), any(), any())).thenReturn(HttpStatus.UNAUTHORIZED);

        callback(ALLOWED, huge);

        org.mockito.ArgumentCaptor<byte[]> read = org.mockito.ArgumentCaptor.forClass(byte[].class);
        verify(ingest).handle(read.capture(), any(), any());
        assertThat(read.getValue()).hasSize(MomoCallbackVerifier.MAX_BODY_BYTES + 1);
    }

    @Test
    void whileMomoIsDisabledTheRouteAnswers404WithNoBodyAndTouchesNothing() throws Exception {
        PaymentProviders none = new PaymentProviders(List.of());
        MockMvc disabled = MockMvcBuilders.standaloneSetup(new PaymentWebhookController(none,
                context.getBean(MomoProperties.class), ingest, context.getBean(WebhookTelemetry.class),
                context.getBean(Clock.class))).build();
        double before = count("disabled");

        disabled.perform(post(MomoCallbackRoute.PATH).content("{}").with(from(ALLOWED))).andExpect(r -> {
            assertThat(r.getResponse().getStatus()).isEqualTo(404);
            assertThat(r.getResponse().getContentAsByteArray()).isEmpty();
        });

        verifyNoInteractions(ingest);
        verify(momo, never()).verifyCallback(any());
        assertThat(count("disabled")).isEqualTo(before + 1);
    }
}
