package com.flowcortex.workflow.application.vendor;

import com.flowcortex.media.api.MediaVendorTaskReported;
import com.flowcortex.media.api.VendorTaskReport;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Turns a worker's vendor-task report into a ledger write. A thrown exception leaves the event
 * publication incomplete, so the registry resubmits it: a paid job's record is never dropped.
 */
@Component
class VendorTaskProgressListener {

    private static final Logger LOG = LoggerFactory.getLogger(VendorTaskProgressListener.class);

    private final VendorJobLedger ledger;
    private final NamedParameterJdbcTemplate jdbc;

    VendorTaskProgressListener(VendorJobLedger ledger, NamedParameterJdbcTemplate jdbc) {
        this.ledger = ledger;
        this.jdbc = jdbc;
    }

    @ApplicationModuleListener
    void on(MediaVendorTaskReported event) {
        VendorTaskReport report = event.report();
        UUID stepId = event.jobId();
        if (!stepBelongsToRun(stepId, event.runId())) {
            LOG.warn("vendor_job outcome=report_ignored run={} step={} reason=unknown_step", event.runId(), stepId);
            return;
        }
        if (report.vendorTaskId() == null && report.status() == VendorTaskReport.Status.RUNNING) {
            ledger.record(event.runId(), stepId, report);
        } else if (report.vendorTaskId() == null) {
            ledger.markByJob(event.runId(), stepId, report.vendor(), report.vendorJobId(), report.status());
        } else if (report.status() == VendorTaskReport.Status.RUNNING) {
            LOG.warn("vendor_job outcome=report_ignored run={} step={} ledger={} reason=running_verdict",
                    event.runId(), stepId, report.vendorTaskId());
        } else {
            ledger.mark(event.runId(), stepId, report.vendorTaskId(), report.status());
        }
    }

    private boolean stepBelongsToRun(UUID stepId, UUID runId) {
        Integer found = jdbc.queryForObject("SELECT count(*) FROM run_step WHERE id = :step AND run_id = :run",
                new MapSqlParameterSource().addValue("step", stepId).addValue("run", runId), Integer.class);
        return found != null && found > 0;
    }
}
