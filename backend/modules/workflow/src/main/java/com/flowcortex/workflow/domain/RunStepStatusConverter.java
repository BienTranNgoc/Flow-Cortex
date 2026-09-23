package com.flowcortex.workflow.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Locale;

/**
 * Persists {@link RunStepStatus} as the lowercase wire/DB form ({@code pending}|{@code
 * running}|{@code completed}|{@code error}); Java enum constants stay upper-case. {@code
 * autoApply} lets {@link RunStep#status} pick this up without an explicit {@code @Convert}.
 */
@Converter(autoApply = true)
public class RunStepStatusConverter implements AttributeConverter<RunStepStatus, String> {

    @Override
    public String convertToDatabaseColumn(RunStepStatus attribute) {
        return attribute == null ? null : attribute.name().toLowerCase(Locale.ROOT);
    }

    @Override
    public RunStepStatus convertToEntityAttribute(String dbData) {
        return dbData == null ? null : RunStepStatus.valueOf(dbData.toUpperCase(Locale.ROOT));
    }
}
