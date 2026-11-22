package com.flowcortex.workflow.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Locale;

/** Persists {@link WorkflowVersionSource} as its lowercase DB form. */
@Converter(autoApply = true)
public class WorkflowVersionSourceConverter implements AttributeConverter<WorkflowVersionSource, String> {

    @Override
    public String convertToDatabaseColumn(WorkflowVersionSource attribute) {
        return attribute == null ? null : attribute.name().toLowerCase(Locale.ROOT);
    }

    @Override
    public WorkflowVersionSource convertToEntityAttribute(String dbData) {
        return dbData == null ? null : WorkflowVersionSource.valueOf(dbData.toUpperCase(Locale.ROOT));
    }
}
