package com.flowcortex.workflow.domain;

import com.flowcortex.workflow.api.WorkflowVisibility;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Locale;

/** Persists {@link WorkflowVisibility} as the lowercase DB form ({@code private}|{@code unlisted}|{@code public}). */
@Converter(autoApply = true)
public class WorkflowVisibilityConverter implements AttributeConverter<WorkflowVisibility, String> {

    @Override
    public String convertToDatabaseColumn(WorkflowVisibility attribute) {
        return attribute == null ? null : attribute.wire();
    }

    @Override
    public WorkflowVisibility convertToEntityAttribute(String dbData) {
        return dbData == null ? null : WorkflowVisibility.valueOf(dbData.toUpperCase(Locale.ROOT));
    }
}
