package com.flowcortex.workflow.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Locale;

/** Persists {@link WorkflowModerationStatus} as the lowercase DB form ({@code visible}|{@code hidden}|{@code removed}). */
@Converter(autoApply = true)
public class WorkflowModerationStatusConverter implements AttributeConverter<WorkflowModerationStatus, String> {

    @Override
    public String convertToDatabaseColumn(WorkflowModerationStatus attribute) {
        return attribute == null ? null : attribute.name().toLowerCase(Locale.ROOT);
    }

    @Override
    public WorkflowModerationStatus convertToEntityAttribute(String dbData) {
        return dbData == null ? null : WorkflowModerationStatus.valueOf(dbData.toUpperCase(Locale.ROOT));
    }
}
