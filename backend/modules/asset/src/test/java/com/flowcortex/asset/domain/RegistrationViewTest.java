package com.flowcortex.asset.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RegistrationViewTest {

    @Test
    void pendingIsPending() {
        assertThat(RegistrationView.of(RegistrationStatus.PENDING)).isEqualTo(RegistrationView.PENDING);
        assertThat(RegistrationView.PENDING.value()).isEqualTo("pending");
    }

    @Test
    void doneIsDone() {
        assertThat(RegistrationView.of(RegistrationStatus.DONE)).isEqualTo(RegistrationView.DONE);
        assertThat(RegistrationView.DONE.value()).isEqualTo("done");
    }

    @Test
    void skippedStuckOrNoRowIsNone() {
        assertThat(RegistrationView.of(RegistrationStatus.SKIPPED)).isEqualTo(RegistrationView.NONE);
        assertThat(RegistrationView.of(RegistrationStatus.STUCK)).isEqualTo(RegistrationView.NONE);
        assertThat(RegistrationView.of(null)).isEqualTo(RegistrationView.NONE);
        assertThat(RegistrationView.NONE.value()).isEqualTo("none");
    }

    @Test
    void aStoredStatusColumnMapsTheSameWay() {
        assertThat(RegistrationView.ofColumn("pending")).isEqualTo(RegistrationView.PENDING);
        assertThat(RegistrationView.ofColumn("done")).isEqualTo(RegistrationView.DONE);
        assertThat(RegistrationView.ofColumn("stuck")).isEqualTo(RegistrationView.NONE);
        assertThat(RegistrationView.ofColumn(null)).isEqualTo(RegistrationView.NONE);
    }
}
