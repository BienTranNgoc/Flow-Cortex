package com.flowcortex.workflow.application.vendor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.flowcortex.media.api.MediaVendorTaskReported;
import com.flowcortex.media.api.VendorTaskReport;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** {@link VendorTaskProgressListener}: which ledger write each report shape is routed to. */
class VendorTaskProgressListenerTest {

    private static final UUID RUN = UUID.randomUUID();
    private static final UUID STEP = UUID.randomUUID();
    private static final UUID LEDGER_ROW = UUID.randomUUID();

    private VendorJobLedger ledger;
    private NamedParameterJdbcTemplate jdbc;
    private VendorTaskProgressListener listener;

    @BeforeEach
    void setUp() {
        ledger = mock(VendorJobLedger.class);
        jdbc = mock(NamedParameterJdbcTemplate.class);
        listener = new VendorTaskProgressListener(ledger, jdbc);
        stepBelongsToRun(1);
    }

    @Test
    void failedVerdictWithoutLedgerIdIsMarkedByJob() {
        listener.on(event(null, VendorTaskReport.Status.FAILED));

        verify(ledger).markByJob(RUN, STEP, "kling", "vendor-job-1", VendorTaskReport.Status.FAILED);
        verify(ledger, never()).mark(any(), any(), any(), any());
        verify(ledger, never()).record(any(), any(), any());
    }

    @Test
    void expiredVerdictWithoutLedgerIdIsMarkedByJob() {
        listener.on(event(null, VendorTaskReport.Status.EXPIRED));

        verify(ledger).markByJob(RUN, STEP, "kling", "vendor-job-1", VendorTaskReport.Status.EXPIRED);
        verify(ledger, never()).mark(any(), any(), any(), any());
    }

    @Test
    void runningReportWithoutLedgerIdRecordsTheJob() {
        VendorTaskReport report = report(null, VendorTaskReport.Status.RUNNING);

        listener.on(new MediaVendorTaskReported(report, RUN, STEP));

        verify(ledger).record(RUN, STEP, report);
        verify(ledger, never()).markByJob(any(), any(), anyString(), anyString(), any());
    }

    @Test
    void runningVerdictCarryingLedgerIdIsIgnored() {
        listener.on(event(LEDGER_ROW, VendorTaskReport.Status.RUNNING));

        verifyNoInteractions(ledger);
    }

    @Test
    void terminalVerdictCarryingLedgerIdMarksThatRow() {
        listener.on(event(LEDGER_ROW, VendorTaskReport.Status.GONE));

        verify(ledger).mark(RUN, STEP, LEDGER_ROW, VendorTaskReport.Status.GONE);
        verify(ledger, never()).markByJob(any(), any(), anyString(), anyString(), any());
    }

    @Test
    void reportForAStepOutsideTheRunTouchesNothing() {
        stepBelongsToRun(0);

        listener.on(event(null, VendorTaskReport.Status.FAILED));

        verifyNoInteractions(ledger);
    }

    private void stepBelongsToRun(int count) {
        when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Integer.class))).thenReturn(count);
    }

    private static MediaVendorTaskReported event(UUID vendorTaskId, VendorTaskReport.Status status) {
        return new MediaVendorTaskReported(report(vendorTaskId, status), RUN, STEP);
    }

    private static VendorTaskReport report(UUID vendorTaskId, VendorTaskReport.Status status) {
        return new VendorTaskReport(vendorTaskId, "kling", "kling-v2", null, null, "vendor-job-1", Map.of(), status,
                Instant.parse("2026-01-01T00:00:00Z"));
    }
}
