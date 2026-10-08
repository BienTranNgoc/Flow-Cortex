package com.flowcortex.workflow.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Locale;

/**
 * Persists {@link ReviewTaskStatus} as the lowercase wire/DB form ({@code pending}|{@code
 * approved}|{@code rejected}) the {@code review_task} CHECK constraint allows.
 */
@Converter(autoApply = true)
public class ReviewTaskStatusConverter implements AttributeConverter<ReviewTaskStatus, String> {

    @Override
    public String convertToDatabaseColumn(ReviewTaskStatus attribute) {
        return attribute == null ? null : attribute.name().toLowerCase(Locale.ROOT);
    }

    @Override
    public ReviewTaskStatus convertToEntityAttribute(String dbData) {
        return dbData == null ? null : ReviewTaskStatus.valueOf(dbData.toUpperCase(Locale.ROOT));
    }
}
