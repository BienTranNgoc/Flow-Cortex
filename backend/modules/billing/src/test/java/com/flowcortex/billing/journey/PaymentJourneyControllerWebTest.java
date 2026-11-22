package com.flowcortex.billing.journey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.flowcortex.shared.ratelimit.InMemoryRateLimitStore;
import com.flowcortex.shared.ratelimit.RateLimitTelemetry;
import com.flowcortex.shared.ratelimit.RateLimiters;
import com.flowcortex.shared.web.LocaleConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.LocaleResolver;

class PaymentJourneyControllerWebTest {

    private static final AnnotationConfigApplicationContext LOCALE =
            new AnnotationConfigApplicationContext(LocaleConfig.class);
    private static final String ROUTE = "/api/v1/billing/journey-events";
    private static final String RETURNED = "{\"event\":\"billing.payer_returned\",\"outcome\":\"waiting\",\"orderRef\":null}";

    @AfterAll
    static void close() {
        LOCALE.close();
    }

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private MockMvc mvc() {
        RateLimiters limiters = new RateLimiters(List.of(new JourneyRateLimits().paymentJourneyMemberLimit()),
                new InMemoryRateLimitStore(), new RateLimitTelemetry(meters, Clock.systemUTC()));
        return MockMvcBuilders
                .standaloneSetup(new PaymentJourneyController(limiters, new JourneyTelemetry(meters)))
                .setControllerAdvice(new JourneyProblemHandler(LOCALE, LOCALE.getBean(LocaleResolver.class)))
                .build();
    }

    private static MockHttpServletRequestBuilder send(String body) {
        return post(ROUTE).principal(() -> "member-a").contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private double refusals() {
        return meters.get("payment.journey.refused").counter().count();
    }

    @Test
    void aValidObservationIsRecordedWith204AndNotCountedAsARefusal() throws Exception {
        mvc().perform(send(RETURNED)).andExpect(status().isNoContent());

        assertThat(refusals()).isZero();
    }

    @Test
    void aBodyWithAnUndeclaredKeyIsRefusedWholeInTheRequestLanguageAndCounted() throws Exception {
        String body = RETURNED.replace("}", ",\"userId\":\"42\"}");

        mvc().perform(send(body).header(HttpHeaders.ACCEPT_LANGUAGE, "vi"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("journey_event_invalid"))
                .andExpect(jsonPath("$.detail")
                        .value("Đây không phải sự kiện hành trình thanh toán mà hệ thống ghi nhận."));
        mvc().perform(send(RETURNED + "x").header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("That is not a payment journey event this service records."));

        assertThat(refusals()).isEqualTo(2.0);
    }

    @Test
    void aBodyOverTheByteCeilingIsRefused() throws Exception {
        String padded = "{\"event\":\"billing.payer_returned\",\"outcome\":\"paid\",\"orderRef\":\"" + "a".repeat(2100) + "\"}";

        mvc().perform(send(padded)).andExpect(status().isBadRequest());
    }

    @Test
    void aNonJsonContentTypeIsRefusedWith415AndNotCounted() throws Exception {
        MockMvc mvc = mvc();

        mvc.perform(post(ROUTE).principal(() -> "member-a").contentType(MediaType.TEXT_PLAIN).content(RETURNED))
                .andExpect(status().isUnsupportedMediaType());

        assertThat(meters.find("payment.journey.refused").counter()).satisfiesAnyOf(
                counter -> assertThat(counter).isNull(), counter -> assertThat(counter.count()).isZero());
    }

    @Test
    void theThirtyFirstRequestIsAnswered429WithRetryAfterAndIsNotARefusal() throws Exception {
        MockMvc mvc = mvc();
        for (int i = 0; i < 30; i++) {
            mvc.perform(send(RETURNED)).andExpect(status().isNoContent());
        }

        mvc.perform(send(RETURNED))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER));

        assertThat(refusals()).isZero();
    }
}
