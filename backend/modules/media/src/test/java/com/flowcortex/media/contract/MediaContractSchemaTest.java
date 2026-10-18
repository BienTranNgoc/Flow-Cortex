package com.flowcortex.media.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.media.api.MediaCapability;
import com.flowcortex.media.api.MediaError;
import com.flowcortex.media.api.MediaJob;
import com.flowcortex.media.api.MediaJobInput;
import com.flowcortex.media.api.MediaOutput;
import com.flowcortex.media.api.MediaProgress;
import com.flowcortex.media.api.MediaResult;
import com.flowcortex.media.api.ProviderJob;
import com.flowcortex.media.api.ProviderScriptData;
import com.flowcortex.media.api.ProviderSpeechData;
import com.flowcortex.media.api.ProviderStoryboardData;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Serializes the hand-written contract records and validates them against the committed
 * {@code contracts/*.schema.json}. A schema edit that the records do not follow (or the reverse)
 * fails here; the invalid cases also prove Bean Validation rejects what the schema rejects.
 */
class MediaContractSchemaTest {

    private static final UUID JOB_ID = UUID.fromString("5f0c7c1e-8f7e-4a53-9a38-2f6d6f3f9c11");
    private static final UUID RUN_ID = UUID.fromString("0b7e1d2c-3a4b-4c5d-8e6f-7a8b9c0d1e2f");
    private static final UUID REVISION_ID = UUID.fromString("a71ea3ba-d72a-4c2c-985c-3a7fd98853c4");
    private static final String IDEMPOTENCY_KEY = "a".repeat(64);
    private static final Instant AT = Instant.parse("2026-09-30T12:00:00Z");

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    private static JsonNode jobTree;
    private static JsonNode resultTree;
    private static JsonNode progressTree;
    private static Schema jobSchema;
    private static Schema resultSchema;
    private static Schema progressSchema;
    private static Schema providerJobSchema;
    private static Schema providerScriptSchema;
    private static Schema providerSpeechSchema;
    private static Schema providerStoryboardSchema;

    @BeforeAll
    static void loadSchemas() throws IOException {
        String dir = Objects.requireNonNull(System.getProperty("flowcortex.contracts.dir"),
                "system property flowcortex.contracts.dir is not set (see modules/media/build.gradle.kts)");
        SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
        jobTree = read(Path.of(dir, "media-job.schema.json"));
        resultTree = read(Path.of(dir, "media-result.schema.json"));
        progressTree = read(Path.of(dir, "media-progress.schema.json"));
        JsonNode providerJobTree = read(Path.of(dir, "provider-job.schema.json"));
        jobSchema = registry.getSchema(jobTree);
        resultSchema = registry.getSchema(resultTree);
        progressSchema = registry.getSchema(progressTree);
        providerJobSchema = registry.getSchema(providerJobTree);
        providerScriptSchema = registry.getSchema(read(Path.of(dir, "provider-script-result.schema.json")));
        providerSpeechSchema = registry.getSchema(read(Path.of(dir, "provider-speech-result.schema.json")));
        providerStoryboardSchema = registry.getSchema(
                read(Path.of(dir, "provider-storyboard-result.schema.json")));
    }

    private static JsonNode read(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return MAPPER.readTree(in);
        }
    }

    static MediaJob job() {
        return new MediaJob("1", JOB_ID, RUN_ID, RUN_ID, 1, MediaCapability.PROBE,
                List.of(new MediaJobInput("source", "flowcortex-uploads", "u-1/up-1")),
                Map.of(), "runs/" + RUN_ID + "/steps/1/", 1, 300, 524_288_000L, AT);
    }

    static MediaResult completedResult() {
        return new MediaResult("1", JOB_ID, RUN_ID, 1, 0, MediaResult.Status.COMPLETED,
                List.of(new MediaOutput("video-details.json", "flowcortex-media",
                        "runs/" + RUN_ID + "/steps/1/video-details.json", "application/json", 812L,
                        Map.of("durationSeconds", 1.0))),
                Map.of("durationSeconds", 1.0), null, AT, "worker-1");
    }

    static MediaResult errorResult() {
        return new MediaResult("1", JOB_ID, RUN_ID, 1, 2, MediaResult.Status.ERROR, null, null,
                new MediaError(MediaError.Code.INPUT_REJECTED, "object missing", false), AT, "worker-1");
    }

    static MediaProgress started() {
        return new MediaProgress("1", JOB_ID, RUN_ID, 1, 0, MediaProgress.Kind.STARTED, null, null, AT);
    }

    static Map<String, Object> scriptParams() {
        return Map.of("profile", "product_ad_v1", "payload", Map.of(
                "productName", "Máy lọc nước",
                "description", "Lọc sạch nước đầu",
                "cta", "Mua ngay"));
    }

    static ProviderJob generateScriptJob() {
        return new ProviderJob("1", JOB_ID, RUN_ID, RUN_ID, 1, "generate_script", scriptParams(),
                List.of(), MediaJob.outputPrefix(RUN_ID, 1, 1), 1, 120, AT);
    }

    static ProviderJob generateSpeechJob() {
        return new ProviderJob("1", JOB_ID, RUN_ID, RUN_ID, 1, "generate_speech",
                Map.of("profile", "vi_voice_v1", "payload", Map.of(
                        "script", "Mua máy lọc nước ngay hôm nay", "sourceRevisionId", REVISION_ID)),
                List.of(), MediaJob.outputPrefix(RUN_ID, 1, 1), 1, 120, AT);
    }

    static ProviderJob generateStoryboardJob() {
        return new ProviderJob("1", JOB_ID, RUN_ID, RUN_ID, 1, "generate_storyboard",
                Map.of("profile", "product_ad_storyboard_v1", "payload", Map.of(
                        "script", "Mua máy lọc nước ngay hôm nay", "sourceRevisionId", REVISION_ID)),
                List.of(), MediaJob.outputPrefix(RUN_ID, 1, 1), 1, 120, AT);
    }

    @Test
    void providerScriptJobConformsToTheProviderJobSchema() {
        assertThat(errors(providerJobSchema, generateScriptJob())).isEmpty();
        assertThat(VALIDATOR.validate(generateScriptJob())).isEmpty();
    }

    @Test
    void providerSpeechJobConformsToTheProviderJobSchema() {
        assertThat(errors(providerJobSchema, generateSpeechJob())).isEmpty();
        assertThat(VALIDATOR.validate(generateSpeechJob())).isEmpty();
    }

    @Test
    void providerStoryboardJobConformsToTheProviderJobSchema() {
        assertThat(errors(providerJobSchema, generateStoryboardJob())).isEmpty();
        assertThat(VALIDATOR.validate(generateStoryboardJob())).isEmpty();
    }

    @Test
    void providerResultDataConformsToItsSchema() {
        assertThat(providerScriptSchema.validate(MAPPER.valueToTree(
                new ProviderScriptData("product_ad_v1", "vi", "Mua máy lọc nước ngay hôm nay")))).isEmpty();
        assertThat(VALIDATOR.validate(
                new ProviderScriptData("product_ad_v1", "vi", "Mua máy lọc nước ngay hôm nay"))).isEmpty();

        assertThat(providerSpeechSchema.validate(MAPPER.valueToTree(
                speechData(4200)))).isEmpty();
        assertThat(VALIDATOR.validate(speechData(4200))).isEmpty();

        ProviderStoryboardData storyboard = new ProviderStoryboardData(
                "product_ad_storyboard_v1", REVISION_ID, "Opening product hook", "Benefit close");
        assertThat(providerStoryboardSchema.validate(MAPPER.valueToTree(storyboard))).isEmpty();
        assertThat(VALIDATOR.validate(storyboard)).isEmpty();
    }

    /** The provider schemas bound what Bean Validation alone cannot, so the case that motivated
     * validating {@code params} is locked here: an over-long product name must be refused. */
    @Test
    void providerScriptPayloadOverItsSchemaBoundIsRejectedByTheSchema() {
        ObjectNode tree = (ObjectNode) MAPPER.valueToTree(generateScriptJob());
        ((ObjectNode) tree.path("params").path("payload")).put("productName", "x".repeat(201));

        assertThat(providerJobSchema.validate(tree)).isNotEmpty();
    }

    @Test
    void providerScriptDataWithTheWrongLanguageIsRejectedBySchemaAndByBeanValidation() {
        ProviderScriptData french = new ProviderScriptData("product_ad_v1", "fr", "script");

        assertThat(providerScriptSchema.validate(MAPPER.valueToTree(french))).isNotEmpty();
        assertThat(VALIDATOR.validate(french)).isNotEmpty();
    }

    @Test
    void providerScriptDataOverEightThousandCharactersIsRejectedBySchemaAndByBeanValidation() {
        ProviderScriptData longScript = new ProviderScriptData("product_ad_v1", "vi", "x".repeat(9000));

        assertThat(providerScriptSchema.validate(MAPPER.valueToTree(longScript))).isNotEmpty();
        assertThat(VALIDATOR.validate(longScript)).isNotEmpty();
    }

    @Test
    void providerSpeechDataWithADurationBelowOneIsRejectedBySchemaAndByBeanValidation() {
        ProviderSpeechData zeroDuration = speechData(0);

        assertThat(providerSpeechSchema.validate(MAPPER.valueToTree(zeroDuration))).isNotEmpty();
        assertThat(VALIDATOR.validate(zeroDuration)).isNotEmpty();
    }

    private static ProviderSpeechData speechData(int durationMs) {
        return new ProviderSpeechData("vi_voice_v1", durationMs, "edge", "vi-VN-HoaiMyNeural",
                "+0%", REVISION_ID, IDEMPOTENCY_KEY, "wav");
    }

    @Test
    void providerJobWhoseCapabilityDoesNotMatchItsParamsIsRejectedByTheSchema() {
        ObjectNode tree = (ObjectNode) MAPPER.valueToTree(generateScriptJob());
        tree.put("capability", "generate_speech");

        assertThat(providerJobSchema.validate(tree)).isNotEmpty();
    }

    @Test
    void providerJobWithAnUnknownParamKeyIsRejectedByTheSchema() {
        ObjectNode tree = (ObjectNode) MAPPER.valueToTree(generateScriptJob());
        ((ObjectNode) tree.path("params")).put("unexpected", "value");

        assertThat(providerJobSchema.validate(tree)).isNotEmpty();
    }

    @Test
    void providerJobWithAnOutputPrefixOutsideTheStepIsRejectedByTheSchemaAndByBeanValidation() {
        ProviderJob escaped = new ProviderJob("1", JOB_ID, RUN_ID, RUN_ID, 1, "generate_script",
                scriptParams(), List.of(), "/etc/", 1, 120, AT);

        assertThat(providerJobSchema.validate(MAPPER.valueToTree(escaped))).isNotEmpty();
        assertThat(VALIDATOR.validate(escaped)).isNotEmpty();
    }

    private static List<Error> errors(Schema schema, Object record) {
        return schema.validate(MAPPER.valueToTree(record));
    }

    @Test
    void jobFixtureConformsToTheJobSchema() {
        assertThat(errors(jobSchema, job())).isEmpty();
        assertThat(VALIDATOR.validate(job())).isEmpty();
    }

    @Test
    void completedAndErrorResultsConformToTheResultSchema() {
        assertThat(errors(resultSchema, completedResult())).isEmpty();
        assertThat(errors(resultSchema, errorResult())).isEmpty();
    }

    @Test
    void startedProgressWithZeroDeliveriesConformsToTheProgressSchema() {
        assertThat(errors(progressSchema, started())).isEmpty();
        assertThat(VALIDATOR.validate(started())).isEmpty();
    }

    @Test
    void jobWithoutCapabilityIsRejectedByTheSchemaAndByBeanValidation() {
        MediaJob job = job();
        MediaJob noCapability = new MediaJob(job.schemaVersion(), job.jobId(), job.runId(), job.correlationId(),
                job.stepOrder(), null, job.inputs(), job.params(), job.outputPrefix(), job.attempt(),
                job.maxRuntimeSeconds(), job.maxInputBytes(), job.issuedAt());

        assertThat(errors(jobSchema, noCapability)).isNotEmpty();
        assertThat(VALIDATOR.validate(noCapability)).isNotEmpty();
    }

    @Test
    void jobInputGivenAsUrlIsRejectedByTheSchema() {
        ObjectNode tree = (ObjectNode) MAPPER.valueToTree(job());
        ObjectNode urlInput = MAPPER.createObjectNode()
                .put("key", "source")
                .put("url", "https://example.com/clip.mp4");
        tree.putArray("inputs").add(urlInput);

        assertThat(jobSchema.validate(tree)).isNotEmpty();
    }

    @Test
    void unknownCapabilityValueIsRejectedByTheSchema() {
        ObjectNode tree = (ObjectNode) MAPPER.valueToTree(job());
        tree.put("capability", "transcode");

        assertThat(jobSchema.validate(tree)).isNotEmpty();
    }

    @Test
        void extractAudioCapabilityAndErrorConformToTheSchemas() {
        MediaJob source = job();
        MediaJob extract = new MediaJob(source.schemaVersion(), source.jobId(), source.runId(),
                source.correlationId(), source.stepOrder(), MediaCapability.EXTRACT_AUDIO,
                source.inputs(), source.params(), source.outputPrefix(), source.attempt(),
                source.maxRuntimeSeconds(), source.maxInputBytes(), source.issuedAt());
        MediaResult failed = new MediaResult("1", JOB_ID, RUN_ID, 1, 0,
                MediaResult.Status.ERROR, null, null,
                new MediaError(MediaError.Code.AUDIO_EXTRACTION_FAILED, "no audio", false),
                AT, "worker-1");

        assertThat(errors(jobSchema, extract)).isEmpty();
        assertThat(errors(resultSchema, failed)).isEmpty();
        }

    static Stream<Arguments> invalidRecords() {
        MediaJob j = job();
        MediaResult r = completedResult();
        MediaProgress p = started();
        return Stream.of(
                Arguments.of("job schemaVersion 2", new MediaJob("2", j.jobId(), j.runId(), j.correlationId(),
                        j.stepOrder(), j.capability(), j.inputs(), j.params(), j.outputPrefix(), j.attempt(),
                        j.maxRuntimeSeconds(), j.maxInputBytes(), j.issuedAt())),
                Arguments.of("job stepOrder 0", new MediaJob(j.schemaVersion(), j.jobId(), j.runId(),
                        j.correlationId(), 0, j.capability(), j.inputs(), j.params(), j.outputPrefix(), j.attempt(),
                        j.maxRuntimeSeconds(), j.maxInputBytes(), j.issuedAt())),
                Arguments.of("job without inputs", new MediaJob(j.schemaVersion(), j.jobId(), j.runId(),
                        j.correlationId(), j.stepOrder(), j.capability(), List.of(), j.params(), j.outputPrefix(),
                        j.attempt(), j.maxRuntimeSeconds(), j.maxInputBytes(), j.issuedAt())),
                Arguments.of("job input with empty objectKey", new MediaJob(j.schemaVersion(), j.jobId(), j.runId(),
                        j.correlationId(), j.stepOrder(), j.capability(),
                        List.of(new MediaJobInput("source", "flowcortex-uploads", "")), j.params(), j.outputPrefix(),
                        j.attempt(), j.maxRuntimeSeconds(), j.maxInputBytes(), j.issuedAt())),
                Arguments.of("job attempt 0", new MediaJob(j.schemaVersion(), j.jobId(), j.runId(),
                        j.correlationId(), j.stepOrder(), j.capability(), j.inputs(), j.params(), j.outputPrefix(), 0,
                        j.maxRuntimeSeconds(), j.maxInputBytes(), j.issuedAt())),
                Arguments.of("job maxRuntimeSeconds 0", new MediaJob(j.schemaVersion(), j.jobId(), j.runId(),
                        j.correlationId(), j.stepOrder(), j.capability(), j.inputs(), j.params(), j.outputPrefix(),
                        j.attempt(), 0, j.maxInputBytes(), j.issuedAt())),
                Arguments.of("job maxInputBytes 0", new MediaJob(j.schemaVersion(), j.jobId(), j.runId(),
                        j.correlationId(), j.stepOrder(), j.capability(), j.inputs(), j.params(), j.outputPrefix(),
                        j.attempt(), j.maxRuntimeSeconds(), 0L, j.issuedAt())),
                Arguments.of("result deliveryCount -1", new MediaResult(r.schemaVersion(), r.jobId(), r.runId(),
                        r.attempt(), -1, r.status(), r.outputs(), r.data(), r.error(), r.finishedAt(), r.workerId())),
                Arguments.of("result output bytes -1", new MediaResult(r.schemaVersion(), r.jobId(), r.runId(),
                        r.attempt(), r.deliveryCount(), r.status(),
                        List.of(new MediaOutput("x.json", "flowcortex-media", "runs/x.json", "application/json", -1L,
                                null)),
                        r.data(), r.error(), r.finishedAt(), r.workerId())),
                Arguments.of("progress percent 101", new MediaProgress(p.schemaVersion(), p.jobId(), p.runId(),
                        p.attempt(), p.deliveryCount(), MediaProgress.Kind.HEARTBEAT, 101, null, p.at())),
                Arguments.of("progress without kind", new MediaProgress(p.schemaVersion(), p.jobId(), p.runId(),
                        p.attempt(), p.deliveryCount(), null, null, null, p.at())),
                Arguments.of("job outputPrefix outside runs/", new MediaJob(j.schemaVersion(), j.jobId(), j.runId(),
                        j.correlationId(), j.stepOrder(), j.capability(), j.inputs(), j.params(), "/etc/",
                        j.attempt(), j.maxRuntimeSeconds(), j.maxInputBytes(), j.issuedAt())),
                Arguments.of("completed result carrying an error block", new MediaResult(r.schemaVersion(),
                        r.jobId(), r.runId(), r.attempt(), r.deliveryCount(), MediaResult.Status.COMPLETED,
                        r.outputs(), r.data(), new MediaError(MediaError.Code.PROBE_FAILED, "x", false),
                        r.finishedAt(), r.workerId())),
                Arguments.of("error result without an error block", new MediaResult(r.schemaVersion(), r.jobId(),
                        r.runId(), r.attempt(), r.deliveryCount(), MediaResult.Status.ERROR, null, null, null,
                        r.finishedAt(), r.workerId())));
    }

    @Test
    void jobWhoseCorrelationIdIsNotTheRunIdIsRejectedByBeanValidation() {
        MediaJob j = job();
        MediaJob uncorrelated = new MediaJob(j.schemaVersion(), j.jobId(), j.runId(), UUID.randomUUID(),
                j.stepOrder(), j.capability(), j.inputs(), j.params(), j.outputPrefix(), j.attempt(),
                j.maxRuntimeSeconds(), j.maxInputBytes(), j.issuedAt());

        assertThat(VALIDATOR.validate(uncorrelated)).isNotEmpty();
        assertThat(MAPPER.valueToTree(uncorrelated).has("correlatedToRun")).isFalse();
    }

    private static Set<String> propertyNames(JsonNode... schemaObjects) {
        Set<String> names = new HashSet<>();
        for (JsonNode schemaObject : schemaObjects) {
            schemaObject.path("properties").properties().forEach(property -> names.add(property.getKey()));
        }
        return names;
    }

    private static Set<String> strings(JsonNode... nodes) {
        Set<String> values = new HashSet<>();
        for (JsonNode node : nodes) {
            if (node.isArray()) {
                node.forEach(value -> values.add(value.asString()));
            } else if (!node.isMissingNode()) {
                values.add(node.asString());
            }
        }
        return values;
    }

    static Stream<Arguments> schemaObjectsAndRecords() {
        JsonNode defs = resultTree.path("$defs");
        return Stream.of(
                Arguments.of("job", propertyNames(jobTree), MediaJob.class),
                Arguments.of("job input", propertyNames(jobTree.path("$defs").path("input")), MediaJobInput.class),
                Arguments.of("result (both branches)",
                        propertyNames(defs.path("completedResult"), defs.path("errorResult")), MediaResult.class),
                Arguments.of("result output", propertyNames(defs.path("output")), MediaOutput.class),
                Arguments.of("result error", propertyNames(defs.path("error")), MediaError.class),
                Arguments.of("progress", propertyNames(progressTree), MediaProgress.class));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("schemaObjectsAndRecords")
    void schemaPropertiesAreExactlyTheRecordComponents(String label, Set<String> schemaProperties, Class<?> record) {
        Set<String> components = Arrays.stream(record.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(Collectors.toSet());

        assertThat(schemaProperties).as("properties of %s", label).isNotEmpty();
        assertThat(components).as("components of %s", record.getSimpleName()).isEqualTo(schemaProperties);
    }

    static Stream<Arguments> schemaEnumsAndJavaEnums() {
        JsonNode defs = resultTree.path("$defs");
        return Stream.of(
                Arguments.of("capability", strings(jobTree.path("$defs").path("capability").path("enum")),
                        MediaCapability.class),
                Arguments.of("result status", strings(
                        defs.path("completedResult").path("properties").path("status").path("const"),
                        defs.path("errorResult").path("properties").path("status").path("const")),
                        MediaResult.Status.class),
                Arguments.of("error code", strings(defs.path("error").path("properties").path("code").path("enum")),
                        MediaError.Code.class),
                Arguments.of("progress kind", strings(progressTree.path("properties").path("kind").path("enum")),
                        MediaProgress.Kind.class));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("schemaEnumsAndJavaEnums")
    void schemaEnumValuesAreExactlyTheJavaEnumValues(String label, Set<String> schemaValues,
            Class<? extends Enum<?>> type) throws ReflectiveOperationException {
        Set<String> javaValues = new HashSet<>();
        Method value = type.getMethod("value");
        for (Enum<?> constant : type.getEnumConstants()) {
            javaValues.add((String) value.invoke(constant));
        }

        assertThat(schemaValues).as("schema enum %s", label).isNotEmpty();
        assertThat(javaValues).as("values of %s", type.getSimpleName()).isEqualTo(schemaValues);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidRecords")
    void schemaAndBeanValidationRejectTheSameInvalidRecord(String label, Object record) {
        Schema schema = switch (record) {
            case MediaJob ignored -> jobSchema;
            case MediaResult ignored -> resultSchema;
            case MediaProgress ignored -> progressSchema;
            default -> throw new IllegalArgumentException(label);
        };
        JsonNode tree = MAPPER.valueToTree(record);

        assertThat(schema.validate(tree)).as("schema errors for %s", label).isNotEmpty();
        assertThat(VALIDATOR.validate(record)).as("bean violations for %s", label).isNotEmpty();
    }
}
