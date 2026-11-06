package com.flowcortex.credit.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.shared.web.LocaleConfig;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class InsufficientCreditsExceptionTest {

    @Test
    void theMessageNamesBalanceRequiredAndShortfall() {
        InsufficientCreditsException e = new InsufficientCreditsException(30, 60);

        try (AnnotationConfigApplicationContext messages = new AnnotationConfigApplicationContext(LocaleConfig.class)) {
            assertThat(messages.getMessage(e.getDetail(), Locale.ENGLISH))
                    .isEqualTo("Not enough credits: you have 30, this run needs 60 (short 30).");
        }
        assertThat(e.balance()).isEqualTo(30);
        assertThat(e.required()).isEqualTo(60);
        assertThat(e.shortfall()).isEqualTo(30);
    }

    @Test
    void aBalanceThatCoversTheRunHasNoShortfall() {
        assertThat(new InsufficientCreditsException(100, 60).shortfall()).isZero();
    }
}
