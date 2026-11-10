package com.flowcortex.workflow.application;

import com.flowcortex.media.api.MediaDownload;
import com.flowcortex.media.api.MediaJobs;
import com.flowcortex.workflow.domain.WorkflowReview;
import com.flowcortex.workflow.domain.WorkflowReviewImage;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The read shapes of reviews; image links are signed when a view is built and expire. */
public final class ReviewViews {

    private static final Map<String, String> EXTENSION_OF_TYPE = Map.of(
            "image/png", "png", "image/jpeg", "jpg", "image/webp", "webp");

    private ReviewViews() {}

    /** One image of a review with a time-limited link to its stored copy. */
    public record ReviewImageView(UUID id, String url, Instant expiresAt) {

        /** Without the URL: it grants the download to whoever holds it until it expires. */
        @Override
        public String toString() {
            return "ReviewImageView[id=" + id + ", url=<redacted>, expiresAt=" + expiresAt + "]";
        }
    }

    /** A review as its author sees it. */
    public record ReviewView(UUID id, int rating, String body, List<ReviewImageView> images, int helpfulCount,
            Instant createdAt, Instant updatedAt) {
    }

    /** A review in a list; {@code authorName} is null when the author's account is gone. */
    public record ReviewListItemView(UUID id, int rating, String body, List<ReviewImageView> images,
            int helpfulCount, Instant createdAt, Instant updatedAt, boolean helpfulByMe, boolean mine,
            String authorName) {
    }

    /** One page of reviews with the size of the filtered set. */
    public record ReviewPage(List<ReviewListItemView> data, long total, int page, int size) {
    }

    /** The answer to a helpful mark: the member's mark now, and the review's count. */
    public record HelpfulResult(boolean helpful, int helpfulCount) {
    }

    static ReviewView view(MediaJobs mediaJobs, WorkflowReview review, List<WorkflowReviewImage> images) {
        return new ReviewView(review.getId(), review.getRating(), review.getBody(), signed(mediaJobs, images),
                review.getHelpfulCount(), review.getCreatedAt(), review.getUpdatedAt());
    }

    static List<ReviewImageView> signed(MediaJobs mediaJobs, List<WorkflowReviewImage> images) {
        return images.stream().map(image -> {
            String extension = EXTENSION_OF_TYPE.getOrDefault(image.getContentType(), "img");
            MediaDownload link = mediaJobs.presignedGet(image.getBucket(), image.getObjectKey(),
                    "review-" + image.getPosition() + "." + extension);
            return new ReviewImageView(image.getId(), link.url(), link.expiresAt());
        }).toList();
    }
}
