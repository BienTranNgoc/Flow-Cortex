package com.flowcortex.billing.refund;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.flowcortex.billing.refund.ChangeOfMind.Refusal;
import com.flowcortex.billing.refund.RefundResponses.RefundRequestView;
import com.flowcortex.billing.refund.RefundResponses.RightView;
import com.flowcortex.billing.refund.RefundResponses.TurnsView;
import com.flowcortex.shared.ratelimit.InMemoryRateLimitStore;
import com.flowcortex.shared.ratelimit.RateLimitTelemetry;
import com.flowcortex.shared.ratelimit.RateLimiters;
import com.flowcortex.shared.web.GlobalExceptionHandler;
import com.flowcortex.shared.web.LocaleConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.LocaleResolver;

class RefundRequestsControllerWebTest {

    private static final AnnotationConfigApplicationContext LOCALE =
            new AnnotationConfigApplicationContext(LocaleConfig.class);
    private static final String MEMBER = UUID.randomUUID().toString();
    private static final String BASE = "/api/v1/billing/transactions/";

    @AfterAll
    static void close() {
        LOCALE.close();
    }

    private final RefundRequests refunds = mock(RefundRequests.class);

    private MockMvc mvc() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        RateLimiters limiters = new RateLimiters(List.of(new RefundRateLimits().refundRequestMemberLimit()),
                new InMemoryRateLimitStore(), new RateLimitTelemetry(meters, Clock.systemUTC()));
        LocaleResolver locales = LOCALE.getBean(LocaleResolver.class);
        return MockMvcBuilders.standaloneSetup(new RefundRequestsController(refunds, limiters))
                .setControllerAdvice(new GlobalExceptionHandler(LOCALE, locales),
                        new RefundProblemHandler(LOCALE, locales))
                .build();
    }

    private static RefundRequestView view() {
        Instant at = Instant.parse("2026-12-01T10:00:00Z");
        return new RefundRequestView(UUID.randomUUID(), "HT-000001", "pending", "Lý do", at, null, null, null,
                null, null);
    }

    private static RightView refused(Refusal refusal) {
        return new RightView(UUID.randomUUID(), 199_000, "VND", "topup", "monthly", 3, null, 0, "refused",
                refusal.value());
    }

    @Test
    void aReasonUnderTenCharactersIsRefusedWith400AndTheFieldAndNothingIsRequested() throws Exception {
        mvc().perform(post(BASE + UUID.randomUUID() + "/refund-request").principal(() -> MEMBER)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"ngắn\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://flow-cortex.dev/problems/validation-failed"))
                .andExpect(jsonPath("$.errors.reason").exists());

        verifyNoInteractions(refunds);
    }

    @Test
    void aMissingBodyAndAnEmptyObjectBothRequestWithNoReason() throws Exception {
        UUID id = UUID.randomUUID();
        when(refunds.request(any(), any(), isNull())).thenReturn(view());
        MockMvc mvc = mvc();

        mvc.perform(post(BASE + id + "/refund-request").principal(() -> MEMBER)).andExpect(status().isOk());
        mvc.perform(post(BASE + id + "/refund-request").principal(() -> MEMBER)
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("HT-000001"));

        verify(refunds, times(2)).request(UUID.fromString(MEMBER), id, null);
    }

    @Test
    void aMalformedIdAnswersTheSameNotFoundAsAnUnknownOneOnEveryRoute() throws Exception {
        UUID unknown = UUID.randomUUID();
        when(refunds.eligibility(any(), any())).thenThrow(new RefundTransactionNotFoundException());
        when(refunds.latest(any(), any())).thenThrow(new RefundTransactionNotFoundException());
        when(refunds.request(any(), any(), any())).thenThrow(new RefundTransactionNotFoundException());
        when(refunds.cancel(any(), any())).thenThrow(new RefundTransactionNotFoundException());
        MockMvc mvc = mvc();

        for (String path : List.of("/refund-eligibility", "/refund-request")) {
            mvc.perform(get(BASE + "abc" + path).principal(() -> MEMBER).header(HttpHeaders.ACCEPT_LANGUAGE, "vi"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("transaction_not_found"))
                    .andExpect(jsonPath("$.detail").value("Không tìm thấy khoản thanh toán này trong tài khoản của bạn"));
            mvc.perform(get(BASE + unknown + path).principal(() -> MEMBER).header(HttpHeaders.ACCEPT_LANGUAGE, "vi"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("transaction_not_found"))
                    .andExpect(jsonPath("$.detail").value("Không tìm thấy khoản thanh toán này trong tài khoản của bạn"));
        }
        for (String path : List.of("/refund-request", "/refund-request/cancel")) {
            mvc.perform(post(BASE + "abc" + path).principal(() -> MEMBER))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("transaction_not_found"));
            mvc.perform(post(BASE + unknown + path).principal(() -> MEMBER))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("transaction_not_found"));
        }
    }

    @Test
    void aRefusedRequestAnswers409WithTheRefusalAndAVietnameseDetail() throws Exception {
        when(refunds.request(any(), any(), any())).thenThrow(new RefundNotEligibleException(
                refused(Refusal.WINDOW_ELAPSED), new TurnsView(true, 0, 2, 6, null)));

        mvc().perform(post(BASE + UUID.randomUUID() + "/refund-request").principal(() -> MEMBER)
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "vi"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("refund_not_eligible"))
                .andExpect(jsonPath("$.refusal").value("window_elapsed"))
                .andExpect(jsonPath("$.detail").value("Khoản thanh toán này không nằm trong quyền hoàn tiền do đổi ý."))
                .andExpect(jsonPath("$.limit").doesNotExist());
    }

    @Test
    void aTurnLimitRefusalCarriesTheLimitTheWindowAndTheNextOpening() throws Exception {
        Instant next = Instant.parse("2027-02-28T08:00:00Z");
        when(refunds.request(any(), any(), any())).thenThrow(new RefundNotEligibleException(
                refused(Refusal.TURN_LIMIT_REACHED), new TurnsView(true, 2, 2, 6, next)));

        mvc().perform(post(BASE + UUID.randomUUID() + "/refund-request").principal(() -> MEMBER))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.refusal").value("turn_limit_reached"))
                .andExpect(jsonPath("$.limit").value(2))
                .andExpect(jsonPath("$.windowMonths").value(6))
                .andExpect(jsonPath("$.nextTurnAt").value("2027-02-28T08:00:00Z"));
    }

    @Test
    void cancellingWithNothingPendingAnswers409() throws Exception {
        when(refunds.cancel(any(), any())).thenThrow(new RefundRequestNotCancellableException());

        mvc().perform(post(BASE + UUID.randomUUID() + "/refund-request/cancel").principal(() -> MEMBER))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("refund_request_not_cancellable"));
    }

    @Test
    void theEleventhRequestOrCancelInAMinuteIsAnswered429AcrossBothRoutesAndWritesNothing() throws Exception {
        when(refunds.request(any(), any(), any())).thenReturn(view());
        when(refunds.cancel(any(), any())).thenReturn(view());
        MockMvc mvc = mvc();
        UUID id = UUID.randomUUID();
        for (int i = 0; i < 5; i++) {
            mvc.perform(post(BASE + id + "/refund-request").principal(() -> MEMBER)).andExpect(status().isOk());
            mvc.perform(post(BASE + id + "/refund-request/cancel").principal(() -> MEMBER)).andExpect(status().isOk());
        }

        mvc.perform(post(BASE + id + "/refund-request").principal(() -> MEMBER))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER));
        mvc.perform(post(BASE + id + "/refund-request/cancel").principal(() -> MEMBER))
                .andExpect(status().isTooManyRequests());

        verify(refunds, times(5)).request(any(), any(), any());
        verify(refunds, times(5)).cancel(any(), any());
    }
}
