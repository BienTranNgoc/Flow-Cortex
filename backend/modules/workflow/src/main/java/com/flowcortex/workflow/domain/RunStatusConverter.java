package com.flowcortex.workflow.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Locale;

/**
 * Persists {@link RunStatus} as the lowercase wire/DB form ({@code running}|{@code
 * completed}|{@code error}); Java enum constants stay upper-case. {@code autoApply} lets
 * {@link WorkflowRun#status} pick this up without an explicit {@code @Convert}.
 */
@Converter(autoApply = true)
public class RunStatusConverter implements AttributeConverter<RunStatus, String> {

    @Override
    public String convertToDatabaseColumn(RunStatus attribute) {
        return attribute == null ? null : attribute.name().toLowerCase(Locale.ROOT);
    }

    @Override
    public RunStatus convertToEntityAttribute(String dbData) {
        return dbData == null ? null : RunStatus.valueOf(dbData.toUpperCase(Locale.ROOT));
    }
}
