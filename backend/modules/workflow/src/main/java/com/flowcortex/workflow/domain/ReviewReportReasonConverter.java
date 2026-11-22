package com.flowcortex.workflow.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Persists {@link ReviewReportReason} as its lowercase code, as {@link RunStatusConverter} does for run status. */
@Converter(autoApply = true)
public class ReviewReportReasonConverter implements AttributeConverter<ReviewReportReason, String> {

    @Override
    public String convertToDatabaseColumn(ReviewReportReason attribute) {
        return attribute == null ? null : attribute.code();
    }

    @Override
    public ReviewReportReason convertToEntityAttribute(String dbData) {
        return dbData == null ? null : ReviewReportReason.fromCode(dbData).orElseThrow(
                () -> new IllegalArgumentException("unknown review report reason: " + dbData));
    }
}
