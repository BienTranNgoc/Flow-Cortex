package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowcortex.shared.web.UnprocessableEntityException;
import com.flowcortex.workflow.domain.ReviewReportReason;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReviewRulesTest {

    private static UUID id() {
        return UUID.randomUUID();
    }

    private static void assertRefused(Runnable call, String field, String key) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(UnprocessableEntityException.class, e -> {
            assertThat(e.getErrors()).containsOnlyKeys(field);
            assertThat(e.getErrors().get(field).code()).isEqualTo(key);
        });
    }

    @Test
    void ratingOneToFiveIsAcceptedAndZeroSixFractionalOrMissingIsRefused() {
        for (int rating = 1; rating <= 5; rating++) {
            assertThat(ReviewRules.rating(rating)).isEqualTo(rating);
        }
        assertThat(ReviewRules.rating(4.0)).isEqualTo(4);
        assertRefused(() -> ReviewRules.rating(0), "rating", "workflow.review.ratingInvalid");
        assertRefused(() -> ReviewRules.rating(6), "rating", "workflow.review.ratingInvalid");
        assertRefused(() -> ReviewRules.rating(-1), "rating", "workflow.review.ratingInvalid");
        assertRefused(() -> ReviewRules.rating(4.5), "rating", "workflow.review.ratingInvalid");
        assertRefused(() -> ReviewRules.rating(null), "rating", "workflow.review.ratingInvalid");
        assertThat(ReviewRules.rating(new BigDecimal("4.0"))).isEqualTo(4);
        assertRefused(() -> ReviewRules.rating(new BigDecimal("4.0000000000000000001")), "rating",
                "workflow.review.ratingInvalid");
        assertRefused(() -> ReviewRules.rating(Double.NaN),"rating", "workflow.review.ratingInvalid");
        assertRefused(() -> ReviewRules.rating(Long.MAX_VALUE), "rating", "workflow.review.ratingInvalid");
    }

    @Test
    void aBlankBodyBecomesNone() {
        assertThat(ReviewRules.body(null)).isNull();
        assertThat(ReviewRules.body("")).isNull();
        assertThat(ReviewRules.body(" \n\t ")).isNull();
        assertThat(ReviewRules.body("  Fast and accurate \n")).isEqualTo("Fast and accurate");
    }

    @Test
    void aBodyOfExactly5000CodePointsIsKeptAnd5001IsRefused() {
        String exact = "a".repeat(5000);
        assertThat(ReviewRules.body(exact)).isEqualTo(exact);
        // An astral character is two chars but one code point.
        String astral = "😀".repeat(5000);
        assertThat(ReviewRules.body(astral)).isEqualTo(astral);
        assertRefused(() -> ReviewRules.body("a".repeat(5001)), "body", "workflow.review.bodyTooLong");
        assertRefused(() -> ReviewRules.body("😀".repeat(5001)), "body", "workflow.review.bodyTooLong");
    }

    @Test
    void imagesSplitIntoKeptIdsAndUploadIdsInOrder() {
        UUID i1 = id();
        UUID i2 = id();
        UUID u3 = id();

        ReviewRules.ImagePlan plan = ReviewRules.planImages(List.of(i2, u3), List.of(i1, i2));

        assertThat(plan.keptIds()).containsExactly(i2);
        assertThat(plan.uploadIds()).containsExactly(u3);
        assertThat(plan.finalOrder()).containsExactly(i2, u3);
    }

    @Test
    void sixImagesOrADuplicateAreRefused() {
        List<UUID> six = List.of(id(), id(), id(), id(), id(), id());
        UUID dup = id();

        assertRefused(() -> ReviewRules.planImages(six, List.of()), "images", "workflow.review.imagesInvalid");
        assertRefused(
                () -> ReviewRules.planImages(List.of(dup, dup), List.of()), "images", "workflow.review.imagesInvalid");
        assertRefused(
                () -> ReviewRules.planImages(List.of(dup, id(), dup), List.of(dup)),
                "images",
                "workflow.review.imagesInvalid");
        List<UUID> withNull = new ArrayList<>();
        withNull.add(null);
        assertRefused(() -> ReviewRules.planImages(withNull, List.of()), "images", "workflow.review.imagesInvalid");
        assertThat(ReviewRules.planImages(six.subList(0, 5), List.of()).finalOrder())
                .hasSize(5);
    }

    @Test
    void anAbsentImagesListKeepsTheCurrentImagesAndAnEmptyOneRemovesThem() {
        UUID i1 = id();
        UUID i2 = id();

        ReviewRules.ImagePlan absent = ReviewRules.planImages(null, List.of(i1, i2));
        assertThat(absent.keptIds()).containsExactly(i1, i2);
        assertThat(absent.uploadIds()).isEmpty();
        assertThat(absent.finalOrder()).containsExactly(i1, i2);

        ReviewRules.ImagePlan empty = ReviewRules.planImages(List.of(), List.of(i1, i2));
        assertThat(empty.keptIds()).isEmpty();
        assertThat(empty.uploadIds()).isEmpty();
        assertThat(empty.finalOrder()).isEmpty();
    }

    @Test
    void reportReasonMustBeOneOfEightAndDetailsAtMost1000() {
        for (ReviewReportReason reason : ReviewReportReason.values()) {
            assertThat(ReviewRules.report(reason.code(), null).reason()).isEqualTo(reason);
        }
        assertThat(ReviewRules.report("spam", "  link farm ").details()).isEqualTo("link farm");
        assertThat(ReviewRules.report("spam", "   ").details()).isNull();
        assertThat(ReviewRules.report("other", "d".repeat(1000)).details()).hasSize(1000);

        assertRefused(() -> ReviewRules.report(null, null), "reason", "workflow.review.reasonInvalid");
        assertRefused(() -> ReviewRules.report("SPAM", null), "reason", "workflow.review.reasonInvalid");
        assertRefused(() -> ReviewRules.report("rude", null), "reason", "workflow.review.reasonInvalid");
        assertRefused(() -> ReviewRules.report("other", "d".repeat(1001)), "details", "workflow.review.detailsTooLong");
    }
}
