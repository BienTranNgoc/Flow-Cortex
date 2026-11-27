package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowcortex.media.api.provider.ProviderScriptData;
import com.flowcortex.workflow.application.DubSegments.Segment;
import com.flowcortex.workflow.application.EditedOutput.FieldEdit;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** An edited output still parses for the reader that takes it next, exactly as the original did. */
class EditedOutputDownstreamTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void anEditedEchoIsTheNextInputVerbatim() {
        String edited = EditedOutput.apply("echo", EditedOutputTest.ECHO, Map.of(),
                List.of(new FieldEdit("", "Hello Hanoi"))).output();

        assertThat(edited).isEqualTo("Hello Hanoi");
    }

    @Test
    void anEditedScriptStillValidatesAsTheProviderResultAndYieldsTheEdit() throws Exception {
        String edited = EditedOutput.apply("generate_script", EditedOutputTest.SCRIPT, Map.of(),
                List.of(new FieldEdit("/script", "Áo thun mát lạnh"))).output();

        ProviderScriptData data = JSON.readValue(edited, ProviderScriptData.class);
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

        assertThat(validator.validate(data)).isEmpty();
        // The approval checkpoint reads the script this way: a textual, non-blank "script".
        assertThat(JSON.readTree(edited).get("script").textValue()).isEqualTo("Áo thun mát lạnh");
    }

    @Test
    void anEditedTranslateStillReadsAsASegmentListWithTheEditedLine() {
        String edited = EditedOutput.apply("translate", EditedOutputTest.TRANSLATE, Map.of(),
                List.of(new FieldEdit("/segments/1/vi", "Cảm ơn bạn"))).output();

        List<Segment> original = DubSegments.read(JSON, EditedOutputTest.TRANSLATE);
        List<Segment> segments = DubSegments.read(JSON, edited);

        assertThat(segments).hasSameSizeAs(original);
        for (int i = 0; i < segments.size(); i++) {
            assertThat(segments.get(i).index()).isEqualTo(original.get(i).index());
            assertThat(segments.get(i).startMs()).isEqualTo(original.get(i).startMs());
            assertThat(segments.get(i).endMs()).isEqualTo(original.get(i).endMs());
            assertThat(segments.get(i).zh()).isEqualTo(original.get(i).zh());
            assertThat(segments.get(i).vi()).isNotNull();
        }
        assertThat(segments.get(0).vi()).isEqualTo(original.get(0).vi());
        assertThat(segments.get(1).vi()).isEqualTo("Cảm ơn bạn");
    }
}
