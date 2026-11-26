package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowcortex.media.api.MediaError;
import com.flowcortex.workflow.api.RunStepFailure;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class StepFailureTaxonomyTest {

    private static final Set<String> KEPT_CHARGE_CODES =
            Set.of("INSUFFICIENT_CREDITS", "APPROVAL_REJECTED", "input_rejected", "probe_failed");

    private static final Pattern FAILURE_CONSTRUCTOR = Pattern.compile(
            "\\bnew\\s+(?:StepFailedException|Rejection|Refusal|Fail|Failure|Failed)\\s*\\(\\s*"
                    + "(?:\"([^\"\\\\]*)\"|([A-Z][A-Z0-9_]*))\\s*[,)]");

    @Test
    void everyCodeRowMatchesTheContract() throws IOException {
        JsonNode contract = contract();
        assertThat(contract.path("version").asInt()).as("version in contracts/step-failure-taxonomy.json").isEqualTo(1);

        List<String> drift = new ArrayList<>();

        Set<String> contractCategories = fieldNames(contract.path("categories"));
        Set<String> tableCategories = new TreeSet<>();
        for (StepFailureTaxonomy.Category category : StepFailureTaxonomy.Category.values()) {
            tableCategories.add(category.wire());
            JsonNode row = contract.path("categories").path(category.wire());
            if (row.isMissingNode()) {
                continue;
            }
            if (row.path("retryable").asBoolean() != category.retryable()
                    || !category.recovery().wire().equals(row.path("recovery").asText())) {
                drift.add("category " + category.wire() + " differs from the contract");
            }
        }
        symmetricDifference(tableCategories, contractCategories)
                .forEach(category -> drift.add("category " + category + " is not in both the table and the contract"));

        Set<String> contractCodes = fieldNames(contract.path("codes"));
        Map<String, StepFailureTaxonomy.Row> rows = StepFailureTaxonomy.rows();
        symmetricDifference(new TreeSet<>(rows.keySet()), contractCodes)
                .forEach(code -> drift.add("code " + code + " is not in both the table and the contract"));
        for (Map.Entry<String, StepFailureTaxonomy.Row> row : new TreeMap<>(rows).entrySet()) {
            JsonNode listed = contract.path("codes").path(row.getKey());
            if (listed.isMissingNode()) {
                continue;
            }
            if (!row.getValue().category().wire().equals(listed.path("category").asText())
                    || !row.getValue().source().wire().equals(listed.path("source").asText())) {
                drift.add("code " + row.getKey() + " has another category or source in the contract");
            }
        }

        JsonNode fallback = contract.path("fallback");
        if (!StepFailureTaxonomy.FALLBACK.category().wire().equals(fallback.path("category").asText())
                || !StepFailureTaxonomy.FALLBACK.source().wire().equals(fallback.path("source").asText())) {
            drift.add("the fallback row differs from the contract");
        }

        assertThat(drift).as("table against contracts/step-failure-taxonomy.json").isEmpty();
    }

    @Test
    void everyMediaErrorCodeHasItsOwnRow() {
        List<String> withoutRow = Arrays.stream(MediaError.Code.values())
                .map(MediaError.Code::value)
                .filter(code -> !StepFailureTaxonomy.rows().containsKey(code))
                .toList();

        assertThat(withoutRow).as("MediaError codes with no row in StepFailureTaxonomy").isEmpty();
    }

    @Test
    void everyWrittenCodeHasItsOwnRow() throws IOException {
        Map<String, Set<String>> written = writtenCodes();

        assertThat(written.keySet())
                .as("the scan reaches every constructor shape and a private constant")
                .contains("contract_violation", "workflow_call_cycle", "load_template_not_found",
                        "workflow_call_unavailable", "connector_member_suspended");
        List<String> withoutRow = written.entrySet().stream()
                .filter(code -> !StepFailureTaxonomy.rows().containsKey(code.getKey()))
                .map(code -> code.getKey() + " in " + code.getValue())
                .toList();

        assertThat(withoutRow).as("codes written to a step failure with no row in StepFailureTaxonomy").isEmpty();
    }

    @Test
    void nullAndUnknownCodesFallToInternalSystemRetry() {
        RunStepFailure expected = new RunStepFailure("internal", "system", true, "retry");

        assertThat(StepFailureTaxonomy.recordFor(null)).isEqualTo(expected);
        assertThat(StepFailureTaxonomy.recordFor("some_future_code")).isEqualTo(expected);
        assertThat(StepFailureTaxonomy.recordFor("")).isEqualTo(expected);
    }

    @Test
    void retryableExactlyWhenRecoveryIsRetryOrTopUp() {
        for (StepFailureTaxonomy.Category category : StepFailureTaxonomy.Category.values()) {
            boolean retries = category.recovery() == StepFailureTaxonomy.Recovery.RETRY
                    || category.recovery() == StepFailureTaxonomy.Recovery.TOP_UP;
            assertThat(category.retryable()).as("retryable of " + category.wire()).isEqualTo(retries);
        }
    }

    @Test
    void sourceIsUserOnlyForTheKeptChargeCodesAndProviderForProviderCategories() {
        StepFailureTaxonomy.rows().forEach((code, row) -> {
            StepFailureTaxonomy.Source expected = KEPT_CHARGE_CODES.contains(code)
                    ? StepFailureTaxonomy.Source.USER
                    : row.category() == StepFailureTaxonomy.Category.PROVIDER_ERROR
                            || row.category() == StepFailureTaxonomy.Category.QUOTA_EXCEEDED
                            ? StepFailureTaxonomy.Source.PROVIDER
                            : StepFailureTaxonomy.Source.SYSTEM;
            assertThat(row.source()).as("source of " + code).isEqualTo(expected);
        });
    }

    @Test
    void leaseExpiredIsTimeout() {
        assertThat(StepFailureTaxonomy.recordFor("lease_expired").category()).isEqualTo("timeout");
    }

    @Test
    void everyListedCodeHasItsCategory() {
        assertCategory("validation", "input_rejected", "probe_failed", "duration_exceeded", "no_audio_stream",
                "speech_too_long", "audio_alignment_out_of_range");
        assertCategory("handoff", "input_unresolved");
        assertCategory("contract", "contract_violation");
        assertCategory("review_rejected", "APPROVAL_REJECTED");
        assertCategory("insufficient_credits", "INSUFFICIENT_CREDITS");
        assertCategory("timeout", "queue_timeout", "lease_expired", "run_timeout");
        assertCategory("provider_error", "provider_request_failed", "provider_response_invalid",
                "provider_capability_unavailable", "provider_unavailable", "translation_invalid");
        assertCategory("quota_exceeded", "provider_quota_exceeded");
        assertCategory("unavailable", "media_disabled", "dub_disabled", "provider_busy");
        assertCategory("processing", "audio_extraction_failed", "audio_alignment_failed");
        assertCategory("internal", "store_unavailable", "unsupported_step_type");
    }

    @Test
    void approvalRejectedIsUserFault() {
        assertThat(StepFailureTaxonomy.recordFor("APPROVAL_REJECTED").source()).isEqualTo("user");
    }

    @Test
    void quotaExceededIsProviderFault() {
        assertThat(StepFailureTaxonomy.recordFor("provider_quota_exceeded").source()).isEqualTo("provider");
    }

    @Test
    void durationExceededIsValidationButSystem() {
        RunStepFailure record = StepFailureTaxonomy.recordFor("duration_exceeded");

        assertThat(record.category()).isEqualTo("validation");
        assertThat(record.source()).isEqualTo("system");
    }

    @Test
    void insufficientCreditsIsRetryableTopUp() {
        RunStepFailure record = StepFailureTaxonomy.recordFor("INSUFFICIENT_CREDITS");

        assertThat(record.retryable()).isTrue();
        assertThat(record.recovery()).isEqualTo("top_up");
    }

    @Test
    void everyW2CodeHasItsCategory() {
        assertCategory("configuration", "workflow_call_cycle", "workflow_call_too_deep", "workflow_call_not_found",
                "workflow_call_unsupported", "workflow_call_child_empty", "workflow_call_needs_upload",
                "workflow_call_has_checkpoint", "workflow_call_invalid_config", "workflow_call_child_incomplete",
                "workflow_call_child_connector_not_owner", "load_template_not_found", "load_template_invalid_template",
                "connector_url_outside_base_url", "connector_invalid_request");
        assertCategory("validation", "load_template_invalid_input", "load_template_missing_input",
                "no_beats_detected");
        assertCategory("unavailable", "workflow_call_unavailable", "connector_member_suspended",
                "connector_turned_off", "connector_not_connected", "connector_usage_disabled",
                "connector_reconnect_needed");
        assertCategory("processing", "workflow_call_child_failed", "workflow_call_child_cancelled",
                "separation_failed", "diarization_failed", "beat_detection_failed");
        assertCategory("provider_error", "connector_upstream_error", "connector_upstream_unavailable",
                "connector_refused");
        assertCategory("quota_exceeded", "connector_rate_limited");
        assertCategory("timeout", "provider_timeout");
    }

    @Test
    void everyIterateCodeHasItsCategory() {
        assertCategory("configuration", "iterate_config_invalid");
        assertCategory("handoff", "iterate_source_not_list", "iterate_item_field_missing");
        assertCategory("processing", "iterate_item_failed");
    }

    @Test
    void aClipCancelledBecauseItsSiblingFailedStaysRetryable() {
        RunStepFailure record = StepFailureTaxonomy.recordFor("sibling_failed");

        assertThat(record.category()).isEqualTo("processing");
        assertThat(record.retryable()).isTrue();
    }

    @Test
    void anExpiredReviewEndsTheRunWithoutARetryAndKeepsNoCharge() {
        RunStepFailure record = StepFailureTaxonomy.recordFor(ReviewExpirySweep.CODE);

        assertThat(record.category()).isEqualTo("review_rejected");
        assertThat(record.source()).isEqualTo("system");
        assertThat(record.retryable()).isFalse();
        assertThat(record.recovery()).isEqualTo("none");
    }

    @Test
    void workflowCallCycleIsConfigurationEditWorkflow() {
        RunStepFailure record = StepFailureTaxonomy.recordFor("workflow_call_cycle");

        assertThat(record.category()).isEqualTo("configuration");
        assertThat(record.retryable()).isFalse();
        assertThat(record.recovery()).isEqualTo("edit_workflow");
    }

    @Test
    void contractViolationIsRetryable() {
        RunStepFailure record = StepFailureTaxonomy.recordFor("contract_violation");

        assertThat(record.category()).isEqualTo("contract");
        assertThat(record.retryable()).isTrue();
        assertThat(record.recovery()).isEqualTo("retry");
    }

    private static void assertCategory(String category, String... codes) {
        for (String code : codes) {
            assertThat(StepFailureTaxonomy.recordFor(code).category()).as("category of " + code).isEqualTo(category);
        }
    }

    private static Set<String> fieldNames(JsonNode object) {
        Set<String> names = new TreeSet<>();
        object.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static Set<String> symmetricDifference(Set<String> a, Set<String> b) {
        Set<String> difference = new TreeSet<>(a);
        difference.addAll(b);
        Set<String> common = new TreeSet<>(a);
        common.retainAll(b);
        difference.removeAll(common);
        return difference;
    }

    private static JsonNode contract() throws IOException {
        String dir = Objects.requireNonNull(System.getProperty("flowcortex.contracts.dir"),
                "system property flowcortex.contracts.dir is not set (see modules/workflow/build.gradle.kts)");
        try (InputStream in = Files.newInputStream(Path.of(dir, "step-failure-taxonomy.json"))) {
            return new ObjectMapper().readTree(in);
        }
    }

    /**
     * The codes written as the first argument of a step failure constructor, with the files writing
     * them. Only the module that declares {@code StepFailedException} is read: another module's
     * {@code Refusal} is an unrelated type that happens to share a constructor name. A bare constant
     * is resolved from a {@code static final String} of the same file; a code built at run time is
     * not seen.
     */
    private static Map<String, Set<String>> writtenCodes() throws IOException {
        String dir = Objects.requireNonNull(System.getProperty("flowcortex.backend.modules.dir"),
                "system property flowcortex.backend.modules.dir is not set (see modules/workflow/build.gradle.kts)");
        Map<String, Set<String>> written = new TreeMap<>();
        try (Stream<Path> modules = Files.list(Path.of(dir))) {
            for (Path module : modules.toList()) {
                Path sources = module.resolve("src/main/java");
                if (!Files.isDirectory(sources)) {
                    continue;
                }
                List<Path> files;
                try (Stream<Path> walk = Files.walk(sources)) {
                    files = walk.filter(file -> file.toString().endsWith(".java")).toList();
                }
                boolean declaresStepFailure = files.stream()
                        .anyMatch(file -> file.getFileName().toString().equals("StepFailedException.java"));
                if (!declaresStepFailure) {
                    continue;
                }
                for (Path file : files) {
                    String source = Files.readString(file);
                    Matcher call = FAILURE_CONSTRUCTOR.matcher(source);
                    while (call.find()) {
                        String code = call.group(1) != null ? call.group(1) : constantValue(source, call.group(2));
                        if (code != null) {
                            written.computeIfAbsent(code, key -> new TreeSet<>()).add(file.getFileName().toString());
                        }
                    }
                }
            }
        }
        return written;
    }

    private static String constantValue(String source, String name) {
        Matcher constant = Pattern.compile("\\bstatic\\s+final\\s+String\\s+" + name + "\\s*=\\s*\"([^\"\\\\]*)\"")
                .matcher(source);
        return constant.find() ? constant.group(1) : null;
    }
}
