package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.connector.api.ConnectorException;
import com.flowcortex.connector.api.ConnectorException.Reason;
import com.flowcortex.shared.web.LocalizedMessage;
import org.junit.jupiter.api.Test;

/** The codes of a failed {@code drive.read} step: decided from the reason and provider reason, never from a message key. */
class DriveReadFailuresTest {

    private static ConnectorException refused(String providerReason) {
        return new ConnectorException(Reason.REFUSED, false, new LocalizedMessage("connector.google.rejected"),
                providerReason, null);
    }

    @Test
    void everyGateReasonGivesTheCodeOfAConnectorCall() {
        for (Reason reason : new Reason[] {Reason.SUSPENDED, Reason.TURNED_OFF, Reason.NOT_CONNECTED,
                Reason.USAGE_DISABLED, Reason.CREDENTIAL_UNREADABLE, Reason.UPSTREAM_UNAVAILABLE}) {
            ConnectorException thrown = ConnectorException.of(reason);

            StepFailedException failure = DriveReadFailures.of(thrown);

            assertThat(failure.code()).as(reason.name())
                    .isEqualTo(ConnectorCallFailures.of(thrown, "google").code());
            assertThat(failure.getMessage()).isEqualTo(ConnectorCallFailures.of(thrown, "google").getMessage());
        }
        ConnectorException limited = new ConnectorException(Reason.RATE_LIMITED, true,
                new LocalizedMessage("connector.google.rateLimited"), null, null);
        assertThat(DriveReadFailures.of(limited).code()).isEqualTo("connector_rate_limited");
    }

    @Test
    void theGateCodesAreTheDocumentedOnes() {
        assertThat(DriveReadFailures.of(ConnectorException.of(Reason.SUSPENDED)).code())
                .isEqualTo("connector_member_suspended");
        assertThat(DriveReadFailures.of(ConnectorException.of(Reason.TURNED_OFF)).code())
                .isEqualTo("connector_turned_off");
        assertThat(DriveReadFailures.of(ConnectorException.of(Reason.NOT_CONNECTED)).code())
                .isEqualTo("connector_not_connected");
        assertThat(DriveReadFailures.of(ConnectorException.of(Reason.USAGE_DISABLED)).code())
                .isEqualTo("connector_usage_disabled");
        assertThat(DriveReadFailures.of(ConnectorException.of(Reason.CREDENTIAL_UNREADABLE)).code())
                .isEqualTo("connector_reconnect_needed");
        assertThat(DriveReadFailures.of(ConnectorException.of(Reason.UPSTREAM_UNAVAILABLE)).code())
                .isEqualTo("connector_upstream_unavailable");
    }

    @Test
    void notConnectedNamesGoogle() {
        assertThat(DriveReadFailures.of(ConnectorException.of(Reason.NOT_CONNECTED)).getMessage())
                .contains("'google'");
    }

    @Test
    void aRefusalHasOneCodePerProviderReason() {
        assertThat(DriveReadFailures.of(refused("folder_no_access")).code()).isEqualTo("drive_folder_no_access");
        assertThat(DriveReadFailures.of(refused("not_a_folder")).code()).isEqualTo("drive_not_a_folder");
        assertThat(DriveReadFailures.of(refused("reconnect")).code()).isEqualTo("drive_reconnect_needed");
        assertThat(DriveReadFailures.of(refused("scope_missing")).code()).isEqualTo("drive_scope_missing");
    }

    @Test
    void anyOtherRefusalIsUpstreamUnavailable() {
        for (String providerReason : new String[] {"not_found", "rejected", "not_native_sheet", "unheard_of", null}) {
            assertThat(DriveReadFailures.of(refused(providerReason)).code()).as(String.valueOf(providerReason))
                    .isEqualTo("connector_upstream_unavailable");
        }
    }

    @Test
    void theCodeFollowsTheProviderReasonNotTheMessageKey() {
        ConnectorException keyOnly = new ConnectorException(Reason.REFUSED, false,
                new LocalizedMessage("connector.google.folderNoAccess"), null, null);
        ConnectorException reasonOnly = new ConnectorException(Reason.REFUSED, false,
                new LocalizedMessage("connector.google.rejected"), "folder_no_access", null);

        assertThat(DriveReadFailures.of(keyOnly).code()).isEqualTo("connector_upstream_unavailable");
        assertThat(DriveReadFailures.of(reasonOnly).code()).isEqualTo("drive_folder_no_access");
    }

    @Test
    void aRevokedConnectionReadAsNotConnectedAsksForAReconnect() {
        ConnectorException revoked = new ConnectorException(Reason.NOT_CONNECTED, false,
                new LocalizedMessage("connector.google.reconnect"), "reconnect", null);

        assertThat(DriveReadFailures.of(revoked).code()).isEqualTo("drive_reconnect_needed");
    }

    @Test
    void aReasonOutsideTheTableIsUpstreamUnavailable() {
        for (Reason reason : new Reason[] {Reason.INVALID_INPUT, Reason.OUT_OF_BASE_URL}) {
            ConnectorException thrown = new ConnectorException(reason, new LocalizedMessage("connector.google.rejected"));

            assertThat(DriveReadFailures.of(thrown).code()).as(reason.name())
                    .isEqualTo("connector_upstream_unavailable");
        }
    }

    @Test
    void theFolderNoAccessMessageTellsToShareAndNamesNoAccount() {
        String message = DriveReadFailures.of(refused("folder_no_access")).getMessage();

        assertThat(message).doesNotContain("@").containsIgnoringCase("share").containsIgnoringCase("anyone with the link");
    }

    @Test
    void noMessageCarriesAnEmailAProviderMessageOrAToken() {
        for (ConnectorException thrown : new ConnectorException[] {refused("folder_no_access"),
                refused("not_a_folder"), refused("reconnect"), refused("scope_missing"), refused("rejected"),
                ConnectorException.of(Reason.NOT_CONNECTED)}) {
            assertThat(DriveReadFailures.of(thrown).getMessage()).doesNotContain("@").doesNotContain("http")
                    .doesNotContain("connector.google");
        }
        assertThat(DriveReadFailures.missingFolder().getMessage()).doesNotContain("@");
        assertThat(DriveReadFailures.notAFolder().getMessage()).doesNotContain("@");
    }

    @Test
    void theAccountWideFailuresStopTheStep() {
        for (Reason reason : new Reason[] {Reason.SUSPENDED, Reason.TURNED_OFF, Reason.NOT_CONNECTED,
                Reason.USAGE_DISABLED, Reason.CREDENTIAL_UNREADABLE}) {
            assertThat(DriveReadFailures.stopsStep(ConnectorException.of(reason))).as(reason.name()).isTrue();
        }
        assertThat(DriveReadFailures.stopsStep(new ConnectorException(Reason.RATE_LIMITED, true,
                new LocalizedMessage("connector.google.rateLimited"), null, null))).isTrue();
        assertThat(DriveReadFailures.stopsStep(refused("reconnect"))).isTrue();
        assertThat(DriveReadFailures.stopsStep(refused("scope_missing"))).isTrue();
    }

    @Test
    void aFailureOfOneFileDoesNotStopTheStep() {
        assertThat(DriveReadFailures.stopsStep(ConnectorException.of(Reason.UPSTREAM_UNAVAILABLE))).isFalse();
        assertThat(DriveReadFailures.stopsStep(refused("not_found"))).isFalse();
        assertThat(DriveReadFailures.stopsStep(refused("rejected"))).isFalse();
        assertThat(DriveReadFailures.stopsStep(refused(null))).isFalse();
    }

    @Test
    void aMissingFolderHasItsOwnCode() {
        assertThat(DriveReadFailures.missingFolder().code()).isEqualTo("drive_folder_missing");
        assertThat(DriveReadFailures.notAFolder().code()).isEqualTo("drive_not_a_folder");
    }

    @Test
    void anUnexpectedFailureIsUpstreamUnavailableWithAFixedMessage() {
        StepFailedException failure = DriveReadFailures.unexpected();

        assertThat(failure.code()).isEqualTo("connector_upstream_unavailable");
        assertThat(failure.getMessage()).doesNotContain("ya29").isEqualTo(
                ConnectorCallFailures.of(Reason.UPSTREAM_UNAVAILABLE, "google").getMessage());
        assertThat(failure.getCause()).isNull();
    }
}
