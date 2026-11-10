package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowcortex.media.api.MediaJobs;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.workflow.api.NewWorkflowStep;
import com.flowcortex.workflow.api.WorkflowView;
import com.flowcortex.workflow.infrastructure.WorkflowDataEntryRepository;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** {@link WorkflowExamples}. */
class WorkflowExamplesTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();
    private static final MediaJobs MEDIA_JOBS = mock(MediaJobs.class);

    private static final StepExecutorRegistry REGISTRY = new StepExecutorRegistry(List.of(
            new EchoStepExecutor(),
            new LoadTemplateStepExecutor(mock(WorkflowDataEntryRepository.class)),
            new RemoteStepExecutor("probe", MEDIA_JOBS, Clock.systemUTC()),
            new RemoteStepExecutor("extract_audio", MEDIA_JOBS, Clock.systemUTC())));

    private final WorkflowAuthoring authoring = mock(WorkflowAuthoring.class);
    private final WorkflowDataStore dataStore = mock(WorkflowDataStore.class);

    private WorkflowExamples catalog(List<String> sources) {
        return new WorkflowExamples(sources, REGISTRY, VALIDATOR, authoring, dataStore);
    }

    private static String example(String id, String titleVi, String descriptionEn, String steps, String dataStore) {
        return """
                {"id":"%s","pattern":"p","title":{"en":"Title","vi":%s},
                 "description":{"en":%s,"vi":"Mô tả"},"sampleInput":null,
                 "steps":%s,"dataStore":%s}
                """.formatted(id, titleVi, descriptionEn, steps, dataStore);
    }

    private static String valid(String id) {
        return example(id, "\"Tiêu đề\"", "\"Description\"", "[{\"name\":\"a\",\"type\":\"echo\"}]", "[]");
    }

    private static String withSteps(String steps) {
        return example("bad", "\"Tiêu đề\"", "\"Description\"", steps, "[]");
    }

    private static String withEntry(String entry) {
        return example("bad", "\"Tiêu đề\"", "\"Description\"", "[{\"name\":\"a\",\"type\":\"echo\"}]",
                "[" + entry + "]");
    }

    @Test
    void theShippedCatalogPassesTheStartupCheck() {
        List<WorkflowExamples.Example> examples = catalog(WorkflowExamples.shipped()).list(Locale.ENGLISH);

        assertThat(examples).extracting(WorkflowExamples.Example::id)
                .containsExactlyInAnyOrder("human-in-the-loop", "load-template", "media-ingest");
    }

    @Test
    void theLoadTemplateExampleCarriesItsThreeStepsAndTheTemplateEntry() throws Exception {
        WorkflowExamples examples = catalog(WorkflowExamples.shipped());
        UUID owner = UUID.randomUUID();
        UUID draftId = UUID.randomUUID();
        when(authoring.createDraft(eq(owner), any(), any(List.class)))
                .thenReturn(new WorkflowView(draftId, owner, "n", false, null, null, List.of(), true, "draft"));

        examples.instantiate(owner, "load-template", Locale.ENGLISH);

        ArgumentCaptor<List<NewWorkflowStep>> steps = ArgumentCaptor.forClass(List.class);
        verify(authoring).createDraft(eq(owner), eq("Fill a template, review, use a field"), steps.capture());
        assertThat(steps.getValue()).extracting(NewWorkflowStep::type)
                .containsExactly("load_template", "APPROVAL_CHECKPOINT", "echo");
        assertThat(steps.getValue().get(2).inputRef()).isEqualTo("{step:1.brief}");
        ArgumentCaptor<List<WorkflowDataStore.Seed>> seeds = ArgumentCaptor.forClass(List.class);
        verify(dataStore).seed(eq(draftId), seeds.capture());
        assertThat(seeds.getValue()).hasSize(1);
        assertThat(seeds.getValue().get(0).key()).isEqualTo("promo-brief");
        assertThat(seeds.getValue().get(0).dataType()).isEqualTo("template");
        JsonNode value = seeds.getValue().get(0).value();
        assertThat(value.path("data").path("brief").asText()).isEqualTo("Spring sale: 20% off every mug this week.");
    }

    @Test
    void anUnknownStepTypeNamesTheExampleAndTheType() {
        assertThatThrownBy(() -> catalog(List.of(withSteps("[{\"name\":\"a\",\"type\":\"image.gen\"}]"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bad")
                .hasMessageContaining("image.gen");
    }

    @Test
    void aStoredInputStepAfterAnEchoIsRefused() {
        assertThatThrownBy(() -> catalog(List.of(
                withSteps("[{\"name\":\"a\",\"type\":\"echo\"},{\"name\":\"b\",\"type\":\"extract_audio\"}]"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bad")
                .hasMessageContaining("extract_audio");
    }

    @Test
    void aRepeatedIdIsRefused() {
        assertThatThrownBy(() -> catalog(List.of(valid("same"), valid("same"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("same")
                .hasMessageContaining("twice");
    }

    @Test
    void aMissingVietnameseTitleIsRefused() {
        assertThatThrownBy(() -> catalog(List.of(
                example("bad", "null", "\"Description\"", "[{\"name\":\"a\",\"type\":\"echo\"}]", "[]"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bad")
                .hasMessageContaining("vi title");
    }

    @Test
    void aMissingEnglishDescriptionIsRefused() {
        assertThatThrownBy(() -> catalog(List.of(
                example("bad", "\"Tiêu đề\"", "null", "[{\"name\":\"a\",\"type\":\"echo\"}]", "[]"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bad")
                .hasMessageContaining("en description");
    }

    @Test
    void aTitleOf256CharactersIsRefused() {
        String longTitle = "\"" + "t".repeat(256) + "\"";

        assertThatThrownBy(() -> catalog(List.of(
                example("bad", longTitle, "\"Description\"", "[{\"name\":\"a\",\"type\":\"echo\"}]", "[]"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bad")
                .hasMessageContaining("255");
    }

    @Test
    void aDataStoreKeyWithASpaceIsRefused() {
        assertThatThrownBy(() -> catalog(List.of(
                withEntry("{\"key\":\"a b\",\"dataType\":\"json\",\"value\":1}"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bad")
                .hasMessageContaining("DATA_STORE_KEY_INVALID");
    }

    @Test
    void aTemplateEntryThatIsNotATemplateIsRefused() {
        assertThatThrownBy(() -> catalog(List.of(
                withEntry("{\"key\":\"t\",\"dataType\":\"template\",\"value\":{\"data\":\"text\"}}"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bad")
                .hasMessageContaining("not a template");
    }

    @Test
    void aRepeatedDataStoreKeyIsRefused() {
        assertThatThrownBy(() -> catalog(List.of(withEntry(
                "{\"key\":\"k\",\"value\":1},{\"key\":\"k\",\"value\":2}"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'k' twice");
    }

    @Test
    void vietnameseIsPickedForViAndEnglishForEverythingElse() {
        WorkflowExamples examples = catalog(List.of(valid("one")));

        assertThat(examples.list(Locale.forLanguageTag("vi")).get(0).title()).isEqualTo("Tiêu đề");
        assertThat(examples.list(Locale.ENGLISH).get(0).title()).isEqualTo("Title");
        assertThat(examples.list(Locale.FRENCH).get(0).title()).isEqualTo("Title");
        assertThat(examples.list(Locale.ENGLISH).get(0).stepCount()).isEqualTo(1);
    }

    @Test
    void anUnknownExampleIsNotFoundAndCreatesNothing() {
        WorkflowExamples examples = catalog(List.of(valid("one")));

        assertThatThrownBy(() -> examples.instantiate(UUID.randomUUID(), "nope", Locale.ENGLISH))
                .isInstanceOf(NotFoundException.class);

        verify(authoring, never()).createDraft(any(), any(), any(List.class));
        verify(dataStore, never()).seed(any(), any());
    }
}
