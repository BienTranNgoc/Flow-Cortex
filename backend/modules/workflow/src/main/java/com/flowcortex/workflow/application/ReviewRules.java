package com.flowcortex.workflow.application;

import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.UnprocessableEntityException;
import com.flowcortex.workflow.domain.ReviewReportReason;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** The input rules of reviews and reports; every refusal is a 422 naming the offending field. */
final class ReviewRules {

    static final int MAX_BODY_CODE_POINTS = 5000;
    static final int MAX_IMAGES = 5;
    static final int MAX_DETAILS_LENGTH = 1000;

    private ReviewRules() {}

    /** The images of a write: ids of current images to keep, upload ids to store, the final order. */
    record ImagePlan(List<UUID> keptIds, List<UUID> uploadIds, List<UUID> finalOrder) {}

    /** A validated report. */
    record Report(ReviewReportReason reason, String details) {}

    static int rating(Number value) {
        if (value == null) {
            throw refusal("rating", new LocalizedMessage("workflow.review.ratingInvalid"));
        }
        BigDecimal exact;
        try {
            exact = new BigDecimal(value.toString());
        } catch (NumberFormatException e) {
            // NaN and infinities have no decimal form.
            throw refusal("rating", new LocalizedMessage("workflow.review.ratingInvalid"));
        }
        if (exact.stripTrailingZeros().scale() > 0
                || exact.compareTo(BigDecimal.ONE) < 0
                || exact.compareTo(BigDecimal.valueOf(5)) > 0) {
            throw refusal("rating", new LocalizedMessage("workflow.review.ratingInvalid"));
        }
        return exact.intValueExact();
    }

    /** The trimmed body, or null when blank. */
    static String body(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.codePointCount(0, trimmed.length()) > MAX_BODY_CODE_POINTS) {
            throw refusal("body", new LocalizedMessage("workflow.review.bodyTooLong"));
        }
        return trimmed;
    }

    /** A null request keeps the current images; an id of a current image is kept, any other is an upload. */
    static ImagePlan planImages(List<UUID> requested, List<UUID> currentImageIds) {
        if (requested == null) {
            return new ImagePlan(List.copyOf(currentImageIds), List.of(), List.copyOf(currentImageIds));
        }
        if (requested.size() > MAX_IMAGES) {
            throw refusal("images", new LocalizedMessage("workflow.review.imagesInvalid"));
        }
        Set<UUID> distinct = new LinkedHashSet<>();
        for (UUID id : requested) {
            if (id == null || !distinct.add(id)) {
                throw refusal("images", new LocalizedMessage("workflow.review.imagesInvalid"));
            }
        }
        Set<UUID> current = new HashSet<>(currentImageIds);
        List<UUID> kept = requested.stream().filter(current::contains).toList();
        List<UUID> uploads = requested.stream().filter(id -> !current.contains(id)).toList();
        return new ImagePlan(kept, uploads, List.copyOf(requested));
    }

    static Report report(String reason, String details) {
        ReviewReportReason parsed =
                reason == null ? null : ReviewReportReason.fromCode(reason).orElse(null);
        if (parsed == null) {
            throw refusal("reason", new LocalizedMessage("workflow.review.reasonInvalid"));
        }
        String trimmed = details == null ? null : details.strip();
        if (trimmed != null && trimmed.isEmpty()) {
            trimmed = null;
        }
        if (trimmed != null && trimmed.codePointCount(0, trimmed.length()) > MAX_DETAILS_LENGTH) {
            throw refusal("details", new LocalizedMessage("workflow.review.detailsTooLong"));
        }
        return new Report(parsed, trimmed);
    }

    private static UnprocessableEntityException refusal(String field, LocalizedMessage message) {
        return new UnprocessableEntityException(message, null, Map.of(field, message));
    }
}
