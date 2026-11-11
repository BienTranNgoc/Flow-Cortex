package com.flowcortex.workflow.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Locale;

/** Persists {@link VisibilityChangeTrigger} as {@code owner}|{@code admin}|{@code key_regenerated}. */
@Converter(autoApply = true)
public class VisibilityChangeTriggerConverter implements AttributeConverter<VisibilityChangeTrigger, String> {

    @Override
    public String convertToDatabaseColumn(VisibilityChangeTrigger attribute) {
        return attribute == null ? null : attribute.name().toLowerCase(Locale.ROOT);
    }

    @Override
    public VisibilityChangeTrigger convertToEntityAttribute(String dbData) {
        return dbData == null ? null : VisibilityChangeTrigger.valueOf(dbData.toUpperCase(Locale.ROOT));
    }
}
