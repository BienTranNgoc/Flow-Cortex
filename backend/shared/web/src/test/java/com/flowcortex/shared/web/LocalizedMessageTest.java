package com.flowcortex.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LocalizedMessageTest {

    @Test
    void aNullArgumentArrayBecomesEmpty() {
        LocalizedMessage message = new LocalizedMessage("problem.unexpected", (Object[]) null);

        assertThat(message.getArguments()).isEmpty();
        assertThat(message).hasToString("problem.unexpected");
    }
}
