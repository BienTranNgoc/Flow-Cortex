package com.flowcortex.workflow.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Locale;

/**
 * Persists {@link RunStepStatus} as the exact lowercase wire/DB form
 * ({@code pending}|{@code running}|{@code completed}|{@code error}) while
 * keeping the Java enum constants upper-case. {@code
 * autoApply} so {@link RunStep#status} picks this up without an explicit
 * {@code @Convert} annotation.
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
