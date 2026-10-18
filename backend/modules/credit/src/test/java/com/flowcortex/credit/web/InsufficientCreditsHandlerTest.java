package com.flowcortex.credit.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.credit.api.InsufficientCreditsException;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;

class InsufficientCreditsHandlerTest {

    @Test
    void aStartTheMemberCannotPayForIs402WithTheNumbersTheFormShows() {
        ProblemDetail detail = new InsufficientCreditsHandler().insufficient(new InsufficientCreditsException(30, 60));

        assertThat(detail.getStatus()).isEqualTo(402);
        assertThat(detail.getType()).isEqualTo(URI.create("https://flow-cortex.dev/problems/insufficient-credits"));
        assertThat(detail.getDetail()).isEqualTo("Not enough credits: you have 30, this run needs 60 (short 30).");
        assertThat(detail.getProperties())
                .containsEntry("code", "INSUFFICIENT_CREDITS")
                .containsEntry("balance", 30L)
                .containsEntry("required", 60L)
                .containsEntry("shortfall", 30L);
    }
}
