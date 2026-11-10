package com.flowcortex.billing.region;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.flowcortex.settings.api.SettingRead;
import com.flowcortex.settings.api.SettingSource;
import com.flowcortex.settings.api.Settings;
import com.flowcortex.shared.web.LocaleConfig;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.LocaleResolver;

class PaymentsRegionGateWebTest {

    private static final AnnotationConfigApplicationContext LOCALE =
            new AnnotationConfigApplicationContext(LocaleConfig.class);
    private static final String VN = "14.160.0.1";
    private static final String FOREIGN = "8.8.8.8";

    @AfterAll
    static void close() {
        LOCALE.close();
    }

    @RestController
    static class Handlers {

        @PaymentsRegionGated
        @GetMapping("/api/v1/marked")
        String marked() {
            return "ok";
        }

        @GetMapping("/api/v1/unmarked")
        String unmarked() {
            return "ok";
        }
    }

    private final MeterRegistry meters = new SimpleMeterRegistry();
    private final Settings settings = mock(Settings.class);

    private void store(String mode) {
        when(settings.get(PaymentsRegionSettings.MODE))
                .thenReturn(new SettingRead<>(mode, mode, SettingSource.STORED));
    }

    private MockMvc mvc(String mode) {
        store(mode);
        PaymentsRegionService service = new PaymentsRegionService(
                Clock.fixed(Instant.parse("2026-11-08T00:00:00Z"), ZoneOffset.UTC));
        service.load();
        PaymentsRegionInterceptor interceptor = new PaymentsRegionInterceptor(service,
                new PaymentsRegionModeSource(settings), new RegionTelemetry(meters));
        return MockMvcBuilders.standaloneSetup(new Handlers())
                .addMappedInterceptors(new String[] {"/api/**"}, interceptor)
                .setControllerAdvice(new PaymentsRegionProblemHandler(LOCALE, LOCALE.getBean(LocaleResolver.class)))
                .build();
    }

    /** A remote address with a private forwarded header beside it: only the remote address may decide. */
    private static RequestPostProcessor from(String address) {
        return request -> {
            request.setRemoteAddr(address);
            request.addHeader("X-Forwarded-For", "10.9.9.9");
            return request;
        };
    }

    @Test
    void enforcedForeignCallerIsRefusedInTheRequestLanguage() throws Exception {
        MockMvc mvc = mvc("enforce");

        mvc.perform(get("/api/v1/marked").with(from(FOREIGN)).header(HttpHeaders.ACCEPT_LANGUAGE, "vi"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("payments_region_unavailable"))
                .andExpect(jsonPath("$.detail").value("Thanh toán hiện chỉ hỗ trợ tại Việt Nam."
                        + " Nếu bạn đang ở Việt Nam và dùng VPN, hãy tắt VPN rồi thử lại."));
        mvc.perform(get("/api/v1/marked").with(from(FOREIGN)).header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("payments_region_unavailable"))
                .andExpect(jsonPath("$.detail").value("Payments are currently available in Vietnam only."
                        + " If you are in Vietnam and using a VPN, turn it off and try again."));
    }

    @Test
    void unmarkedHandlersAreNeverGated() throws Exception {
        mvc("enforce").perform(get("/api/v1/unmarked").with(from(FOREIGN))).andExpect(status().isOk());
    }

    @Test
    void shadowPassesAForeignCallerButCountsIt() throws Exception {
        mvc("shadow").perform(get("/api/v1/marked").with(from(FOREIGN)).header(HttpHeaders.ACCEPT_LANGUAGE, "vi-VN"))
                .andExpect(status().isOk());

        assertThat(meters.get("payments.region.verdict")
                .tag("verdict", "foreign").tag("mode", "shadow").tag("vi_signal", "true").tag("refused", "false")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void enforcePassesVnAndUnknownCallers() throws Exception {
        MockMvc mvc = mvc("enforce");

        mvc.perform(get("/api/v1/marked").with(from(VN))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/marked").with(from("10.1.2.3"))).andExpect(status().isOk());
    }

    @Test
    void offDoesNotPlaceTheCaller() throws Exception {
        mvc("off").perform(get("/api/v1/marked").with(from(FOREIGN))).andExpect(status().isOk());

        assertThat(meters.getMeters()).isEmpty();
    }

    @Test
    void theAddressPlacedIsTheContainersRemoteAddressNotTheForwardedHeader() throws Exception {
        MockMvc mvc = mvc("enforce");

        mvc.perform(get("/api/v1/marked").with(from(VN))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/marked").with(from(FOREIGN))).andExpect(status().isForbidden());
    }

    @Test
    void aStoredModeChangeAppliesToTheNextRequest() throws Exception {
        MockMvc mvc = mvc("enforce");

        mvc.perform(get("/api/v1/marked").with(from(FOREIGN))).andExpect(status().isForbidden());
        store("off");

        mvc.perform(get("/api/v1/marked").with(from(FOREIGN))).andExpect(status().isOk());
    }

    @Test
    void anUngatedHandlerNeverReadsTheMode() throws Exception {
        MockMvc mvc = mvc("enforce");
        clearInvocations(settings);

        mvc.perform(get("/api/v1/unmarked").with(from(FOREIGN))).andExpect(status().isOk());

        verifyNoInteractions(settings);
    }
}
