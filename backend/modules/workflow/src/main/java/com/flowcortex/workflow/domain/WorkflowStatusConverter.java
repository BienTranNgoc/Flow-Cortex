package com.flowcortex.workflow.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Locale;

/** Persists {@link WorkflowStatus} as the lowercase DB form ({@code draft}|{@code published}). */
@Converter(autoApply = true)
public class WorkflowStatusConverter implements AttributeConverter<WorkflowStatus, String> {

    @Override
    public String convertToDatabaseColumn(WorkflowStatus attribute) {
        return attribute == null ? null : attribute.name().toLowerCase(Locale.ROOT);
    }

    @Override
    public WorkflowStatus convertToEntityAttribute(String dbData) {
        return dbData == null ? null : WorkflowStatus.valueOf(dbData.toUpperCase(Locale.ROOT));
    }
}
