package com.flowcortex.workflow.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.workflow.api.NewWorkflowStep;
import com.flowcortex.workflow.api.WorkflowView;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The catalog of example workflows shipped in the jar. Every example is checked once at startup against the
 * live step registry and the data-store rules, so a broken example stops the application instead of reaching a
 * member.
 */
@Component
public class WorkflowExamples {

    static final int MAX_TITLE_CHARS = 255;

    private static final String CATALOG = "classpath:workflow-examples/*.json";
    private static final String VIETNAMESE = "vi";
    private static final String ENGLISH = "en";
    private static final List<String> LANGUAGES = List.of(VIETNAMESE, ENGLISH);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** An example as a member sees it, in one language. */
    public record Example(String id, String pattern, String title, String description, String sampleInput,
            List<String> stepTypes) {

        public int stepCount() {
            return stepTypes.size();
        }
    }

    private record Entry(String key, String dataType, JsonNode value) {}

    private record Definition(String id, String pattern, Map<String, String> titles,
            Map<String, String> descriptions, String sampleInput, List<NewWorkflowStep> steps, List<Entry> entries) {

        Example in(String language) {
            return new Example(id, pattern, titles.get(language), descriptions.get(language), sampleInput,
                    steps.stream().map(NewWorkflowStep::type).toList());
        }
    }

    private final Map<String, Definition> catalog = new LinkedHashMap<>();
    private final WorkflowAuthoring authoring;
    private final WorkflowDataStore dataStore;

    @Autowired
    WorkflowExamples(StepExecutorRegistry registry, Validator validator, WorkflowAuthoring authoring,
            WorkflowDataStore dataStore) {
        this(shipped(), registry, validator, authoring, dataStore);
    }

    WorkflowExamples(List<String> sources, StepExecutorRegistry registry, Validator validator,
            WorkflowAuthoring authoring, WorkflowDataStore dataStore) {
        this.authoring = authoring;
        this.dataStore = dataStore;
        for (int index = 0; index < sources.size(); index++) {
            Definition definition = parse(sources.get(index), index, registry, validator);
            if (catalog.putIfAbsent(definition.id(), definition) != null) {
                throw new IllegalStateException("Workflow example " + definition.id() + ": the id is used twice");
            }
        }
    }

    /** The JSON text of every example file on the classpath, in file-name order. */
    static List<String> shipped() {
        try {
            Resource[] files = new PathMatchingResourcePatternResolver().getResources(CATALOG);
            List<String> sources = new ArrayList<>();
            for (Resource file : Arrays.stream(files)
                    .sorted(Comparator.comparing(Resource::getFilename)).toList()) {
                sources.add(new String(file.getContentAsByteArray(), StandardCharsets.UTF_8));
            }
            return sources;
        } catch (IOException e) {
            throw new UncheckedIOException("Workflow examples could not be read from " + CATALOG, e);
        }
    }

    /** Every example with title and description in Vietnamese for a {@code vi} locale, English otherwise. */
    public List<Example> list(Locale locale) {
        String language = language(locale);
        return catalog.values().stream().map(definition -> definition.in(language)).toList();
    }

    /** Creates the caller's own private draft from the example, with its data-store entries, atomically. */
    @Transactional
    public WorkflowView instantiate(UUID ownerId, String exampleId, Locale locale) {
        Definition definition = catalog.get(exampleId);
        if (definition == null) {
            throw new NotFoundException(new LocalizedMessage("workflow.example.notFound", exampleId));
        }
        WorkflowView draft = authoring.createDraft(ownerId, definition.titles().get(language(locale)),
                definition.steps());
        dataStore.seed(draft.id(), definition.entries().stream()
                .map(entry -> new WorkflowDataStore.Seed(entry.key(), entry.dataType(), entry.value()))
                .toList());
        return draft;
    }

    private static String language(Locale locale) {
        return locale != null && VIETNAMESE.equals(locale.getLanguage()) ? VIETNAMESE : ENGLISH;
    }

    private static Definition parse(String source, int index, StepExecutorRegistry registry, Validator validator) {
        JsonNode root;
        try {
            root = JSON.readTree(source);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Workflow example #" + (index + 1) + " is not valid JSON", e);
        }
        String id = root != null && root.path("id").isTextual() ? root.get("id").asText() : null;
        if (id == null || id.isBlank()) {
            throw new IllegalStateException("Workflow example #" + (index + 1) + " has no id");
        }
        String label = "Workflow example " + id;

        String pattern = text(root, "pattern");
        if (pattern == null || pattern.isBlank()) {
            throw fault(label, "has no pattern");
        }
        Map<String, String> titles = localized(root, "title", label);
        Map<String, String> descriptions = localized(root, "description", label);
        for (String language : LANGUAGES) {
            if (titles.get(language).length() > MAX_TITLE_CHARS) {
                throw fault(label, "has a " + language + " title longer than " + MAX_TITLE_CHARS + " characters");
            }
        }
        JsonNode sample = root.get("sampleInput");
        if (sample != null && !sample.isNull() && !sample.isTextual()) {
            throw fault(label, "has a sampleInput that is not text");
        }
        String sampleInput = sample == null || sample.isNull() ? null : sample.asText();

        List<NewWorkflowStep> steps = steps(root.get("steps"), label);
        Set<ConstraintViolation<KnownStepTypes.Definition>> violations = validator.validate(
                new KnownStepTypes.Definition(steps, registry.knownTypes(), registry.storedInputTypes(),
                        registry.remoteTypes()));
        if (!violations.isEmpty()) {
            throw fault(label, "has invalid steps: " + violations.stream()
                    .map(violation -> violation.getPropertyPath() + " " + violation.getMessage())
                    .sorted()
                    .collect(Collectors.joining("; ")));
        }
        return new Definition(id, pattern, titles, descriptions, sampleInput, steps,
                entries(root.get("dataStore"), label));
    }

    private static Map<String, String> localized(JsonNode root, String field, String label) {
        JsonNode node = root.get(field);
        Map<String, String> values = new LinkedHashMap<>();
        for (String language : LANGUAGES) {
            JsonNode value = node == null ? null : node.get(language);
            if (value == null || !value.isTextual() || value.asText().isBlank()) {
                throw fault(label, "has no " + language + " " + field);
            }
            values.put(language, value.asText());
        }
        return values;
    }

    private static List<NewWorkflowStep> steps(JsonNode node, String label) {
        if (node == null || !node.isArray() || node.isEmpty()) {
            throw fault(label, "has no steps");
        }
        List<NewWorkflowStep> steps = new ArrayList<>();
        for (int i = 0; i < node.size(); i++) {
            JsonNode step = node.get(i);
            String type = text(step, "type");
            if (type == null || type.isBlank()) {
                throw fault(label, "has a step " + (i + 1) + " without a type");
            }
            JsonNode config = step.get("config");
            steps.add(new NewWorkflowStep(text(step, "name"), type, text(step, "inputRef"),
                    config == null || config.isNull() ? null : config.toString()));
        }
        return steps;
    }

    private static List<Entry> entries(JsonNode node, String label) {
        List<Entry> entries = new ArrayList<>();
        if (node == null || node.isNull()) {
            return entries;
        }
        if (!node.isArray()) {
            throw fault(label, "has a dataStore that is not a list");
        }
        for (JsonNode item : node) {
            String key = text(item, "key");
            String dataType = text(item, "dataType");
            JsonNode value = item.get("value");
            boolean repeated = entries.stream().anyMatch(entry -> entry.key().equals(key));
            DataStoreRules.Fault rule = DataStoreRules.check(key, dataType, value, repeated, entries.size());
            if (rule != null) {
                throw fault(label, "has a data-store entry '" + key + "' that breaks the store rules: "
                        + rule.code() + " on " + rule.field());
            }
            if (repeated) {
                throw fault(label, "holds the data-store key '" + key + "' twice");
            }
            if ("template".equals(dataType)) {
                String shape = TemplateLoad.shapeFault(value);
                if (shape != null) {
                    throw fault(label, "has a template entry '" + key + "' that is not a template: " + shape);
                }
            }
            entries.add(new Entry(key, dataType, value));
        }
        return entries;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    private static IllegalStateException fault(String label, String problem) {
        return new IllegalStateException(label + " " + problem);
    }
}
