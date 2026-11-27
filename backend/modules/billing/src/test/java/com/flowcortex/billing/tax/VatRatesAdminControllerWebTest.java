package com.flowcortex.billing.tax;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class VatRatesAdminControllerWebTest {

    private static final UUID ACTOR = UUID.fromString("7d1b5a0e-3c1f-4f55-9a43-0a2f5d9b6c11");
    private static final UUID PUBLISHED = UUID.fromString("0a2f5d9b-6c11-4f55-9a43-7d1b5a0e3c1f");

    private final VatRates rates = mock(VatRates.class);
    private final VatRatePublisher publisher = mock(VatRatePublisher.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new VatRatesAdminController(rates, publisher)).build();

    private static MockHttpServletRequestBuilder set(String body) {
        return post("/api/v1/admin/vat-rates").principal(ACTOR::toString)
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    void aWholeRateIsPublishedAndTheNewRowIdAnswered() throws Exception {
        VatRateEntry row = new VatRateEntry(PUBLISHED, 800, LocalDate.of(2026, 11, 24), null, null);
        when(publisher.publish(any(), eq(ACTOR))).thenReturn(new PublishedVatRate(PUBLISHED, List.of(row)));

        mvc.perform(set("{\"rateBps\":800,\"sourceNote\":\"decree\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publishedRateId").value(PUBLISHED.toString()))
                .andExpect(jsonPath("$.rates[0].rateBps").value(800));

        verify(publisher).publish(new PublishVatRate("VN", "it_services", 800, "decree"), ACTOR);
    }

    @Test
    void aFractionalRateIsRefusedNotRoundedDown() throws Exception {
        mvc.perform(set("{\"rateBps\":850.5}")).andExpect(status().isBadRequest());

        verifyNoInteractions(publisher);
    }

    @Test
    void aRateAboveAHundredPercentIsRefused() throws Exception {
        mvc.perform(set("{\"rateBps\":10001}")).andExpect(status().isBadRequest());

        verifyNoInteractions(publisher);
    }

    @Test
    void aMissingRateIsRefused() throws Exception {
        mvc.perform(set("{}")).andExpect(status().isBadRequest());

        verifyNoInteractions(publisher);
    }

    @Test
    void anUnknownCategoryIsRefused() throws Exception {
        mvc.perform(set("{\"rateBps\":800,\"category\":\"finance\"}")).andExpect(status().isBadRequest());

        verifyNoInteractions(publisher);
    }
}
