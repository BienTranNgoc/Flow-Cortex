package com.flowcortex.credit.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.credit.api.InsufficientCreditsException;
import com.flowcortex.shared.web.LocaleConfig;
import java.net.URI;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ProblemDetail;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.LocaleResolver;

class InsufficientCreditsHandlerTest {

    private static final AnnotationConfigApplicationContext LOCALE =
            new AnnotationConfigApplicationContext(LocaleConfig.class);

    private final InsufficientCreditsHandler handler =
            new InsufficientCreditsHandler(LOCALE, LOCALE.getBean(LocaleResolver.class));

    @AfterAll
    static void close() {
        LOCALE.close();
    }

    @Test
    void aStartTheMemberCannotPayForIs402WithTheNumbersTheFormShows() {
        ProblemDetail detail = handler.insufficient(new InsufficientCreditsException(30, 60),
                new MockHttpServletRequest());

        assertThat(detail.getStatus()).isEqualTo(402);
        assertThat(detail.getType()).isEqualTo(URI.create("https://flow-cortex.dev/problems/insufficient-credits"));
        assertThat(detail.getDetail()).isEqualTo("Not enough credits: you have 30, this run needs 60 (short 30).");
        assertThat(detail.getProperties())
                .containsEntry("code", "INSUFFICIENT_CREDITS")
                .containsEntry("balance", 30L)
                .containsEntry("required", 60L)
                .containsEntry("shortfall", 30L);
    }

    @Test
    void aRequestInVietnameseGetsTheVietnameseDetailAndTheSameNumbers() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.ACCEPT_LANGUAGE, "vi");

        ProblemDetail detail = handler.insufficient(new InsufficientCreditsException(30, 60), request);

        assertThat(detail.getDetail()).isEqualTo("Không đủ credit: bạn có 30, lượt chạy này cần 60 (thiếu 30).");
        assertThat(detail.getProperties()).containsEntry("shortfall", 30L);
    }
}
