package com.flowcortex.user.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class AuditResourceTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private record Article(String id, String title, String name, String email, String slug) {}

    @Test
    void thePathVariableNamedByResourceParamWins() {
        AuditResource.Result result =
                AuditResource.of("id", Map.of("id", "P1"), Map.of("id", "R1", "title", "Hello"), MAPPER);

        assertThat(result.id()).isEqualTo("P1");
        assertThat(result.label()).isEqualTo("Hello");
    }

    @Test
    void withoutAPathVariableTheResponseIdIsUsed() {
        AuditResource.Result result = AuditResource.of("id", Map.of(), Map.of("id", "R1", "name", "Hoa"), MAPPER);

        assertThat(result.id()).isEqualTo("R1");
        assertThat(result.label()).isEqualTo("Hoa");
    }

    @Test
    void anEmptyResourceParamTakesTheResponseIdNotTheParentPathVariable() {
        AuditResource.Result result =
                AuditResource.of("", Map.of("id", "WORKFLOW"), Map.of("id", "STEP", "name", "Echo"), MAPPER);

        assertThat(result.id()).isEqualTo("STEP");
        assertThat(result.label()).isEqualTo("Echo");
    }

    @Test
    void aNamedResponseFieldBeatsTheResponseIdWhenNoPathVariableMatches() {
        AuditResource.Result result =
                AuditResource.of("code", Map.of(), Map.of("code", "editor", "id", "3f9a"), MAPPER);

        assertThat(result.id()).isEqualTo("editor");
    }

    @Test
    void neitherGivesNull() {
        AuditResource.Result result = AuditResource.of("id", Map.of(), Map.of("other", "x"), MAPPER);

        assertThat(result.id()).isNull();
        assertThat(result.label()).isNull();
    }

    @Test
    void aNullResponseGivesTheIdFromThePathAndNoLabel() {
        AuditResource.Result result = AuditResource.of("id", Map.of("id", "9a0f"), null, MAPPER);

        assertThat(result.id()).isEqualTo("9a0f");
        assertThat(result.label()).isNull();
    }

    @Test
    void aNumberPathVariableIsKeptAsText() {
        AuditResource.Result result = AuditResource.of("number", Map.of("number", "1042"), null, MAPPER);

        assertThat(result.id()).isEqualTo("1042");
    }

    @Test
    void aNumericResponseIdIsReadAsText() {
        AuditResource.Result result = AuditResource.of("id", Map.of(), Map.of("id", 1042), MAPPER);

        assertThat(result.id()).isEqualTo("1042");
    }

    @Test
    void theLabelOrderIsTitleNameEmailSlug() {
        assertThat(AuditResource.of("id", Map.of(), new Article("1", "T", "N", "E", "S"), MAPPER)
                        .label())
                .isEqualTo("T");
        assertThat(AuditResource.of("id", Map.of(), new Article("1", null, "N", "E", "S"), MAPPER)
                        .label())
                .isEqualTo("N");
        assertThat(AuditResource.of("id", Map.of(), new Article("1", null, null, "E", "S"), MAPPER)
                        .label())
                .isEqualTo("E");
        assertThat(AuditResource.of("id", Map.of(), new Article("1", null, null, null, "S"), MAPPER)
                        .label())
                .isEqualTo("S");
    }

    @Test
    void blankLabelValuesAreSkipped() {
        AuditResource.Result result = AuditResource.of("id", Map.of(), new Article("1", "  ", "", "e@x.io", null), MAPPER);

        assertThat(result.label()).isEqualTo("e@x.io");
    }

    @Test
    void aLongLabelIsCutTo200AndALongIdTo100() {
        AuditResource.Result result = AuditResource.of(
                "id", Map.of("id", "i".repeat(150)), Map.of("title", "t".repeat(300)), MAPPER);

        assertThat(result.label()).hasSize(200);
        assertThat(result.id()).hasSize(100);
    }
}
