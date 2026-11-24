package com.flowcortex.workflow.application;

import com.flowcortex.workflow.api.RunStepFailure;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Turns a step's error code into its failure record: the one table the API, the retry rule and the
 * run page read. Held equal to {@code contracts/step-failure-taxonomy.json} by a test; a code the
 * table does not list, and no code, read as {@link #FALLBACK}. Pure: it keeps nothing per failure.
 */
final class StepFailureTaxonomy {

    /** What the member should do next; {@code retry} and {@code top_up} are the retryable ones. */
    enum Recovery {
        RETRY("retry"),
        TOP_UP("top_up"),
        CHANGE_INPUT("change_input"),
        EDIT_WORKFLOW("edit_workflow"),
        NONE("none");

        private final String wire;

        Recovery(String wire) {
            this.wire = wire;
        }

        String wire() {
            return wire;
        }
    }

    /** Whose failure it is; {@code user} is exactly the set of codes whose charge the credit module keeps. */
    enum Source {
        USER("user"),
        PROVIDER("provider"),
        SYSTEM("system");

        private final String wire;

        Source(String wire) {
            this.wire = wire;
        }

        String wire() {
            return wire;
        }
    }

    /** The closed category set; retryability and recovery belong to the category, never to a single code. */
    enum Category {
        VALIDATION("validation", false, Recovery.CHANGE_INPUT),
        HANDOFF("handoff", false, Recovery.EDIT_WORKFLOW),
        CONFIGURATION("configuration", false, Recovery.EDIT_WORKFLOW),
        CONTRACT("contract", true, Recovery.RETRY),
        REVIEW_REJECTED("review_rejected", false, Recovery.NONE),
        INSUFFICIENT_CREDITS("insufficient_credits", true, Recovery.TOP_UP),
        TIMEOUT("timeout", true, Recovery.RETRY),
        PROVIDER_ERROR("provider_error", true, Recovery.RETRY),
        QUOTA_EXCEEDED("quota_exceeded", true, Recovery.RETRY),
        UNAVAILABLE("unavailable", true, Recovery.RETRY),
        PROCESSING("processing", true, Recovery.RETRY),
        INTERNAL("internal", true, Recovery.RETRY);

        private final String wire;
        private final boolean retryable;
        private final Recovery recovery;

        Category(String wire, boolean retryable, Recovery recovery) {
            this.wire = wire;
            this.retryable = retryable;
            this.recovery = recovery;
        }

        String wire() {
            return wire;
        }

        boolean retryable() {
            return retryable;
        }

        Recovery recovery() {
            return recovery;
        }
    }

    /** The category and source of one code. */
    record Row(Category category, Source source) {}

    static final Row FALLBACK = new Row(Category.INTERNAL, Source.SYSTEM);

    private static final Map<String, Row> ROWS = table();

    private StepFailureTaxonomy() {}

    /** The record of a stored error code; {@code null} and unlisted codes read as the fallback row. */
    static RunStepFailure recordFor(String code) {
        Row row = code == null ? FALLBACK : ROWS.getOrDefault(code, FALLBACK);
        Category category = row.category();
        return new RunStepFailure(category.wire(), row.source().wire(), category.retryable(),
                category.recovery().wire());
    }

    /** Every code row, in table order. */
    static Map<String, Row> rows() {
        return ROWS;
    }

    private static Map<String, Row> table() {
        Map<String, Row> rows = new LinkedHashMap<>();
        add(rows, Category.VALIDATION, Source.USER, "input_rejected", "probe_failed");
        add(rows, Category.VALIDATION, Source.SYSTEM, "duration_exceeded", "no_audio_stream", "speech_too_long",
                "audio_alignment_out_of_range", "load_template_invalid_input", "load_template_missing_input",
                "no_beats_detected");
        add(rows, Category.HANDOFF, Source.SYSTEM, "input_unresolved");
        add(rows, Category.CONFIGURATION, Source.SYSTEM, "workflow_call_cycle", "workflow_call_too_deep",
                "workflow_call_not_found", "workflow_call_unsupported", "workflow_call_child_empty",
                "workflow_call_needs_upload", "workflow_call_has_checkpoint", "workflow_call_invalid_config",
                "workflow_call_child_incomplete", "workflow_call_child_connector_not_owner", "load_template_not_found",
                "load_template_invalid_template", "connector_url_outside_base_url", "connector_invalid_request");
        add(rows, Category.REVIEW_REJECTED, Source.USER, "APPROVAL_REJECTED");
        add(rows, Category.INSUFFICIENT_CREDITS, Source.USER, "INSUFFICIENT_CREDITS");
        add(rows, Category.CONTRACT, Source.SYSTEM, "contract_violation");
        add(rows, Category.TIMEOUT, Source.SYSTEM, "queue_timeout", "lease_expired", "run_timeout", "provider_timeout");
        add(rows, Category.PROVIDER_ERROR, Source.PROVIDER, "provider_request_failed", "provider_response_invalid",
                "provider_capability_unavailable", "provider_unavailable", "translation_invalid",
                "connector_upstream_error", "connector_upstream_unavailable", "connector_refused");
        add(rows, Category.QUOTA_EXCEEDED, Source.PROVIDER, "provider_quota_exceeded", "connector_rate_limited");
        add(rows, Category.UNAVAILABLE, Source.SYSTEM, "media_disabled", "dub_disabled", "provider_busy",
                "workflow_call_unavailable", "connector_member_suspended", "connector_turned_off",
                "connector_not_connected", "connector_usage_disabled", "connector_reconnect_needed");
        add(rows, Category.PROCESSING, Source.SYSTEM, "audio_extraction_failed", "audio_alignment_failed",
                "workflow_call_child_failed", "workflow_call_child_cancelled", "separation_failed",
                "diarization_failed", "beat_detection_failed");
        add(rows, Category.INTERNAL, Source.SYSTEM, "store_unavailable", "unsupported_step_type");
        return Collections.unmodifiableMap(rows);
    }

    private static void add(Map<String, Row> rows, Category category, Source source, String... codes) {
        for (String code : codes) {
            if (rows.put(code, new Row(category, source)) != null) {
                throw new IllegalStateException("Step failure code listed twice: " + code);
            }
        }
    }
}
