package com.flowcortex.connector.application;

import static com.flowcortex.connector.api.ConnectorException.Reason.NOT_CONNECTED;
import static com.flowcortex.connector.api.ConnectorException.Reason.SUSPENDED;
import static com.flowcortex.connector.api.ConnectorException.Reason.TURNED_OFF;
import static com.flowcortex.connector.api.ConnectorException.Reason.USAGE_DISABLED;
import static com.flowcortex.connector.application.ConnectorUseGate.Operation.MANAGE;
import static com.flowcortex.connector.application.ConnectorUseGate.Operation.USE;
import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.connector.api.ConnectorException;
import com.flowcortex.connector.application.ConnectorUseGate.GateInput;
import com.flowcortex.connector.application.ConnectorUseGate.Operation;
import com.flowcortex.connector.application.ConnectorUseGate.Verdict;
import org.junit.jupiter.api.Test;

class ConnectorUseGateTest {

    private static Verdict decide(boolean suspended, boolean switchOn, boolean connected, boolean usageOn,
            Operation operation) {
        return ConnectorUseGate.decide(new GateInput(suspended, switchOn, connected, usageOn, operation));
    }

    @Test
    void aSuspendedMemberIsRefusedWhateverElseHolds() {
        Verdict verdict = decide(true, true, true, true, USE);

        assertThat(verdict.allowed()).isFalse();
        assertThat(verdict.reason()).isEqualTo(SUSPENDED);
    }

    @Test
    void aSwitchedOffConnectorIsRefused() {
        assertThat(decide(false, false, true, true, USE).reason()).isEqualTo(TURNED_OFF);
    }

    @Test
    void anAbsentOrInactiveConnectionIsNotConnected() {
        assertThat(decide(false, true, false, true, USE).reason()).isEqualTo(NOT_CONNECTED);
        assertThat(decide(false, true, false, true, MANAGE).reason()).isEqualTo(NOT_CONNECTED);
    }

    @Test
    void useWithUsageOffIsRefused() {
        assertThat(decide(false, true, true, false, USE).reason()).isEqualTo(USAGE_DISABLED);
    }

    @Test
    void manageWithUsageOffIsAllowed() {
        assertThat(decide(false, true, true, false, MANAGE).allowed()).isTrue();
    }

    @Test
    void allClearIsAllowedForBothOperations() {
        assertThat(decide(false, true, true, true, USE).allowed()).isTrue();
        assertThat(decide(false, true, true, true, MANAGE).allowed()).isTrue();
        assertThat(decide(false, true, true, true, USE).reason()).isNull();
    }

    @Test
    void switchOffBeatsNotConnected() {
        assertThat(decide(false, false, false, true, USE).reason()).isEqualTo(TURNED_OFF);
    }

    @Test
    void suspendedBeatsNotConnected() {
        assertThat(decide(true, true, false, true, USE).reason()).isEqualTo(SUSPENDED);
    }

    @Test
    void aRefusalBecomesTheTypedExceptionOfItsReason() {
        ConnectorException exception = decide(false, true, true, false, USE).exception();

        assertThat(exception.reason()).isEqualTo(USAGE_DISABLED);
        assertThat(exception.retryable()).isFalse();
        assertThat(exception.detail().code()).isEqualTo("connector.usageDisabled");
    }
}
