package com.flowcortex.workflow.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Locale;

/**
 * Persists {@link RunStatus} as the exact lowercase wire/DB form ({@code
 * running}|{@code completed}|{@code error}) while keeping the Java enum
 * constants upper-case (this codebase's convention). {@code
 * autoApply} so {@link WorkflowRun#status} picks this up without an explicit
 * {@code @Convert} annotation.
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
