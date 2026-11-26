package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.workflow.application.ImportGrading.Issue;
import com.flowcortex.workflow.application.ImportGrading.Review;
import com.flowcortex.workflow.application.ImportGrading.Severity;
import com.flowcortex.workflow.application.OutboundTargets.Target;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ImportGradingTest {

    private static final Instant AT = Instant.parse("2026-11-09T10:00:00Z");

    private static WorkflowArtifact artifact(int steps) {
        List<StepDefinition> list = new ArrayList<>();
        for (int i = 1; i <= steps; i++) {
            list.add(new StepDefinition(i, null, "echo", null, null));
        }
        return new WorkflowArtifact(1, AT, "Demo", list, List.of());
    }

    private static WorkflowArtifact artifactWithStore(WorkflowDataStore.Seed... entries) {
        return new WorkflowArtifact(1, AT, "Demo", List.of(new StepDefinition(1, null, "echo", null, null)),
                List.of(entries));
    }

    private static WorkflowDataStore.Seed entry(String key, String dataType, String json) {
        try {
            return new WorkflowDataStore.Seed(key, dataType, new ObjectMapper().readTree(json));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static Review gradeStore(WorkflowDataStore.Seed... entries) {
        return ImportGrading.grade(artifactWithStore(entries), Map.of(), Map.of(), List.of(), List.of());
    }

    private static Review grade(int steps, Map<String, String> definition, Map<Integer, LocalizedMessage> readiness) {
        return ImportGrading.grade(artifact(steps), definition, readiness, List.of(), List.of());
    }

    @Test
    void anUnknownTypeIsBlockingOnType() {
        Review review = grade(2, Map.of("steps[1].type", "unknown capability"), Map.of());

        assertThat(review.blocked()).isTrue();
        assertThat(review.issues()).singleElement().satisfies(issue -> {
            assertThat(issue.code()).isEqualTo("step_type_unavailable");
            assertThat(issue.severity()).isEqualTo(Severity.BLOCKING);
            assertThat(issue.stepOrder()).isEqualTo(2);
            assertThat(issue.field()).isEqualTo("type");
            assertThat(issue.message().code()).isEqualTo("workflow.artifact.issue.step_type_unavailable");
        });
    }

    @Test
    void aConfigFaultIsBlockingOnConfig() {
        Review review = grade(1, Map.of("steps[0].config", "outputInputs is wrong"), Map.of());

        assertThat(review.blocked()).isTrue();
        assertThat(review.issues()).singleElement().satisfies(issue -> {
            assertThat(issue.code()).isEqualTo("step_config_invalid");
            assertThat(issue.severity()).isEqualTo(Severity.BLOCKING);
            assertThat(issue.stepOrder()).isEqualTo(1);
            assertThat(issue.field()).isEqualTo("config");
        });
    }

    @Test
    void aFaultOnAnotherPathIsStillBlockingAndNeverDropped() {
        Review review = grade(1, Map.of("steps", "something", "steps[0].name", "bad"), Map.of());

        assertThat(review.blocked()).isTrue();
        assertThat(review.issues()).extracting(Issue::code).containsOnly("step_config_invalid");
        assertThat(review.issues()).hasSize(2);
    }

    @Test
    void aReferenceToALaterStepIsAdvisoryOnInputRef() {
        Review review = grade(2, Map.of(), Map.of(1, new LocalizedMessage("workflow.incomplete.notEarlierStep", "2")));

        assertThat(review.blocked()).isFalse();
        assertThat(review.issues()).singleElement().satisfies(issue -> {
            assertThat(issue.code()).isEqualTo("reference_not_earlier");
            assertThat(issue.severity()).isEqualTo(Severity.ADVISORY);
            assertThat(issue.stepOrder()).isEqualTo(1);
            assertThat(issue.field()).isEqualTo("inputRef");
        });
    }

    @Test
    void anEmptyProfileIsAdvisory() {
        Review review = grade(1, Map.of(), Map.of(1, new LocalizedMessage("workflow.incomplete.profileEmpty")));

        assertThat(review.issues()).singleElement().satisfies(issue -> {
            assertThat(issue.code()).isEqualTo("profile_empty");
            assertThat(issue.severity()).isEqualTo(Severity.ADVISORY);
            assertThat(issue.field()).isEqualTo("config");
        });
    }

    @Test
    void noStepsIsAdvisory() {
        Review review = grade(0, Map.of(), Map.of(0, new LocalizedMessage("workflow.incomplete.noSteps")));

        assertThat(review.blocked()).isFalse();
        assertThat(review.issues()).singleElement().satisfies(issue -> {
            assertThat(issue.code()).isEqualTo("no_steps");
            assertThat(issue.severity()).isEqualTo(Severity.ADVISORY);
            assertThat(issue.stepOrder()).isNull();
            assertThat(issue.field()).isNull();
        });
    }

    @Test
    void anUnrecognisedReadinessReasonIsAdvisoryWithItsOwnMessage() {
        LocalizedMessage reason = new LocalizedMessage("workflow.incomplete.referenceMalformed", "x");

        Review review = grade(2, Map.of(), Map.of(2, reason));

        assertThat(review.issues()).singleElement().satisfies(issue -> {
            assertThat(issue.code()).isEqualTo("run_start_reason");
            assertThat(issue.severity()).isEqualTo(Severity.ADVISORY);
            assertThat(issue.stepOrder()).isEqualTo(2);
            assertThat(issue.message()).isSameAs(reason);
        });
    }

    @Test
    void aConfigNotObjectReasonIsNotGivenItsOwnCode() {
        Review review = grade(1, Map.of(), Map.of(1, new LocalizedMessage("workflow.incomplete.configNotObject")));

        assertThat(review.issues()).extracting(Issue::code).containsExactly("run_start_reason");
    }

    @Test
    void onlyAdvisoryIssuesAreNotBlocked() {
        Review review = grade(2, Map.of(), Map.of(
                1, new LocalizedMessage("workflow.incomplete.profileEmpty"),
                2, new LocalizedMessage("workflow.incomplete.notEarlierStep", "3")));

        assertThat(review.issues()).hasSize(2);
        assertThat(review.blocked()).isFalse();
    }

    @Test
    void anOutboundTargetMakesTheReviewOutbound() {
        Target target = new Target(1, "fixture.send", "url", "https://hooks.example.com/x");

        Review review = ImportGrading.grade(artifact(1), Map.of(), Map.of(), List.of(target), List.of());

        assertThat(review.outbound()).isTrue();
        assertThat(review.targets()).containsExactly(target);
        assertThat(review.blocked()).isFalse();
        assertThat(grade(1, Map.of(), Map.of()).outbound()).isFalse();
    }

    @Test
    void aCallToAWorkflowTheImporterCannotSeeIsBlockingOnConfig() {
        Review review = ImportGrading.grade(artifact(3), Map.of(), Map.of(), List.of(), List.of(2));

        assertThat(review.blocked()).isTrue();
        assertThat(review.issues()).singleElement().satisfies(issue -> {
            assertThat(issue.code()).isEqualTo("reference_unresolved");
            assertThat(issue.severity()).isEqualTo(Severity.BLOCKING);
            assertThat(issue.stepOrder()).isEqualTo(2);
            assertThat(issue.field()).isEqualTo("config");
            assertThat(issue.message().code()).isEqualTo("workflow.artifact.issue.reference_unresolved");
        });
    }

    @Test
    void aDataStoreEntryTheStoreAcceptsIsNotAnIssue() {
        Review review = gradeStore(entry("promo", "template", "{\"data\":{\"a\":1}}"), entry("notes", "json", "[1]"));

        assertThat(review.issues()).isEmpty();
        assertThat(review.blocked()).isFalse();
    }

    @Test
    void anEntryTheStoreRefusesIsBlockingAtItsFieldWithTheStoresOwnMessage() {
        Review review = gradeStore(entry("fine", "json", "1"), entry("bad key", "json", "1"),
                entry("big", "json", "\"" + "x".repeat(DataStoreRules.MAX_VALUE_BYTES) + "\""),
                entry("type", "Not A Type", "1"));

        assertThat(review.blocked()).isTrue();
        assertThat(review.issues()).extracting(Issue::code).containsOnly("data_store_entry_invalid");
        assertThat(review.issues()).extracting(Issue::severity).containsOnly(Severity.BLOCKING);
        assertThat(review.issues()).extracting(Issue::field)
                .containsExactly("dataStore[1].key", "dataStore[2].value", "dataStore[3].dataType");
        assertThat(review.issues()).extracting(issue -> issue.message().code()).containsExactly(
                "workflow.dataStore.keyInvalid", "workflow.dataStore.valueTooLarge", "workflow.dataStore.typeInvalid");
        assertThat(review.issues()).extracting(Issue::stepOrder).containsOnlyNulls();
    }

    @Test
    void aRepeatedKeyIsBlockingBecauseTheStoreWouldKeepOnlyTheLaterOne() {
        Review review = gradeStore(entry("a", "json", "1"), entry("b", "json", "1"), entry("a", "json", "2"));

        assertThat(review.issues()).singleElement().satisfies(issue -> {
            assertThat(issue.code()).isEqualTo("data_store_key_duplicate");
            assertThat(issue.severity()).isEqualTo(Severity.BLOCKING);
            assertThat(issue.field()).isEqualTo("dataStore[2].key");
        });
    }

    @Test
    void moreEntriesThanTheQuotaIsOneBlockingIssueAtTheFirstEntryOverIt() {
        WorkflowDataStore.Seed[] entries = new WorkflowDataStore.Seed[DataStoreRules.MAX_KEYS + 50];
        for (int i = 0; i < entries.length; i++) {
            entries[i] = entry("k" + i, "json", "1");
        }

        Review review = gradeStore(entries);

        assertThat(review.issues()).singleElement().satisfies(issue -> {
            assertThat(issue.code()).isEqualTo("data_store_entry_invalid");
            assertThat(issue.field()).isEqualTo("dataStore[" + DataStoreRules.MAX_KEYS + "].key");
            assertThat(issue.message().code()).isEqualTo("workflow.dataStore.quotaExceeded");
        });
    }

    @Test
    void aThousandBadEntriesReportNoMoreThanTheCap() {
        WorkflowDataStore.Seed[] entries = new WorkflowDataStore.Seed[1_000];
        for (int i = 0; i < entries.length; i++) {
            entries[i] = entry("bad key " + i, "json", "1");
        }

        assertThat(gradeStore(entries).issues()).hasSize(ImportGrading.MAX_DATA_STORE_ISSUES);
    }

    @Test
    void issuesAreOrderedByStepOrder() {
        Review review = grade(3, Map.of("steps[2].type", "x"), Map.of(
                0, new LocalizedMessage("workflow.incomplete.noSteps"),
                1, new LocalizedMessage("workflow.incomplete.profileEmpty")));

        assertThat(review.issues()).extracting(Issue::code)
                .containsExactly("no_steps", "profile_empty", "step_type_unavailable");
    }
}
