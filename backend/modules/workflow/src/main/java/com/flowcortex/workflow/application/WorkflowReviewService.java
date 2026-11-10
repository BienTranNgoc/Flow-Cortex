package com.flowcortex.workflow.application;

import com.flowcortex.media.api.MediaAssetInput;
import com.flowcortex.media.api.MediaJobs;
import com.flowcortex.media.api.StoredAttachment;
import com.flowcortex.media.api.UploadGrant;
import com.flowcortex.media.api.UploadPurpose;
import com.flowcortex.media.api.UploadRejectedException;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.LocalizedStatusException;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.shared.web.UnprocessableEntityException;
import com.flowcortex.user.api.UserDirectory;
import com.flowcortex.workflow.application.ReviewViews.HelpfulResult;
import com.flowcortex.workflow.application.ReviewViews.ReviewListItemView;
import com.flowcortex.workflow.application.ReviewViews.ReviewPage;
import com.flowcortex.workflow.application.ReviewViews.ReviewView;
import com.flowcortex.workflow.domain.ReviewSort;
import com.flowcortex.workflow.domain.RunStatus;
import com.flowcortex.workflow.domain.Workflow;
import com.flowcortex.workflow.domain.WorkflowReview;
import com.flowcortex.workflow.domain.WorkflowReviewImage;
import com.flowcortex.workflow.infrastructure.RatingCount;
import com.flowcortex.workflow.infrastructure.WorkflowRepository;
import com.flowcortex.workflow.infrastructure.WorkflowReviewHelpfulRepository;
import com.flowcortex.workflow.infrastructure.WorkflowReviewImageRepository;
import com.flowcortex.workflow.infrastructure.WorkflowReviewReportRepository;
import com.flowcortex.workflow.infrastructure.WorkflowReviewRepository;
import com.flowcortex.workflow.infrastructure.WorkflowRunRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reviews of workflows: one per member, written by members who completed a run, rated and marked helpful
 * by others. Sight is decided by the workflow visibility query on every call. {@code write} and
 * {@code deleteMine} run no method-level transaction: image copies are kept before the write transaction
 * and released after it, and neither may happen inside a database transaction.
 */
@Service
public class WorkflowReviewService {

    static final long MAX_IMAGE_BYTES = UploadPurpose.REVIEW_IMAGE_MAX_BYTES;

    private static final Logger LOG = LoggerFactory.getLogger(WorkflowReviewService.class);

    private final WorkflowRepository workflows;
    private final WorkflowRunRepository runs;
    private final WorkflowReviewRepository reviews;
    private final WorkflowReviewImageRepository images;
    private final WorkflowReviewHelpfulRepository helpful;
    private final WorkflowReviewReportRepository reports;
    private final MediaJobs mediaJobs;
    private final UserDirectory users;
    private final TransactionTemplate transaction;
    private final Clock clock;

    WorkflowReviewService(WorkflowRepository workflows, WorkflowRunRepository runs,
            WorkflowReviewRepository reviews, WorkflowReviewImageRepository images,
            WorkflowReviewHelpfulRepository helpful, WorkflowReviewReportRepository reports, MediaJobs mediaJobs,
            UserDirectory users, PlatformTransactionManager transactionManager, Clock clock) {
        this.workflows = workflows;
        this.runs = runs;
        this.reviews = reviews;
        this.images = images;
        this.helpful = helpful;
        this.reports = reports;
        this.mediaJobs = mediaJobs;
        this.users = users;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** What a committed write leaves: the review, its images in order, and the stored copies it dropped. */
    private record Written(WorkflowReview review, List<WorkflowReviewImage> images, List<StoredAttachment> dropped) {
    }

    /**
     * Creates or replaces the caller's review. {@code images} {@code null} keeps the current images; a list keeps
     * the ids of current images, stores the other ids as new uploads and drops the rest.
     */
    public ReviewView write(UUID callerId, UUID workflowId, Number rating, String body, List<UUID> imageIds) {
        Workflow workflow = requireVisible(callerId, workflowId);
        int stars = ReviewRules.rating(rating);
        String text = ReviewRules.body(body);

        WorkflowReview current = reviews.findByWorkflowIdAndUserId(workflowId, callerId).orElse(null);
        List<UUID> currentImageIds = current == null
                ? List.of()
                : images.findByReviewIdOrderByPosition(current.getId()).stream()
                        .map(WorkflowReviewImage::getId).toList();
        ReviewRules.ImagePlan plan = ReviewRules.planImages(imageIds, currentImageIds);
        if (current == null) {
            requireMayCreate(workflow, callerId);
        }

        List<StoredAttachment> keptNow = keepUploads(callerId, workflowId, plan.uploadIds());
        Written written;
        try {
            written = transaction.execute(status -> persist(workflow, callerId, stars, text, imageIds == null ? null
                    : plan, keptNow));
        } catch (RuntimeException e) {
            release(keptNow, "write_failed", current == null ? null : current.getId());
            throw e;
        }
        release(written.dropped(), "replaced", written.review().getId());
        return ReviewViews.view(mediaJobs, written.review(), written.images());
    }

    @Transactional(readOnly = true)
    public ReviewView mine(UUID callerId, UUID workflowId) {
        requireVisible(callerId, workflowId);
        WorkflowReview review = reviews.findByWorkflowIdAndUserId(workflowId, callerId)
                .orElseThrow(WorkflowReviewService::reviewNotFound);
        return ReviewViews.view(mediaJobs, review, images.findByReviewIdOrderByPosition(review.getId()));
    }

    /** Removes the caller's review with its marks, reports and images, then releases the stored copies. */
    public void deleteMine(UUID callerId, UUID workflowId) {
        requireVisible(callerId, workflowId);
        UUID[] deletedId = new UUID[1];
        List<StoredAttachment> dropped = transaction.execute(status -> {
            WorkflowReview review = reviews.lockByWorkflowIdAndUserId(workflowId, callerId)
                    .orElseThrow(WorkflowReviewService::reviewNotFound);
            List<StoredAttachment> stored = images.findByReviewIdOrderByPosition(review.getId()).stream()
                    .map(WorkflowReviewService::storedOf).toList();
            reviews.delete(review);
            deletedId[0] = review.getId();
            return stored;
        });
        release(dropped, "deleted", deletedId[0]);
    }

    /** The upload grant for one review image, after the visibility check and the media switch. */
    @Transactional(readOnly = true)
    public UploadGrant imageGrant(UUID callerId, UUID workflowId, String fileName, long size) {
        requireVisible(callerId, workflowId);
        requireMediaOn();
        return mediaJobs.prepareUpload(UploadPurpose.REVIEW_IMAGE, callerId, fileName, size);
    }

    @Transactional(readOnly = true)
    public RatingSummary summary(UUID callerId, UUID workflowId) {
        requireVisible(callerId, workflowId);
        Map<Integer, Long> counts = new HashMap<>();
        for (RatingCount count : reviews.ratingCounts(workflowId)) {
            counts.put(count.getRating(), count.getTotal());
        }
        return RatingSummary.of(counts);
    }

    @Transactional(readOnly = true)
    public ReviewPage page(UUID callerId, UUID workflowId, ReviewSort sort, Integer rating, boolean withComment,
            boolean withImages, int page, int size) {
        requireVisible(callerId, workflowId);
        List<WorkflowReview> found = reviews.page(workflowId, rating, withComment, withImages, sort,
                PageRequest.of(page, size));
        long total = reviews.count(workflowId, rating, withComment, withImages);
        if (found.isEmpty()) {
            return new ReviewPage(List.of(), total, page, size);
        }
        List<UUID> ids = found.stream().map(WorkflowReview::getId).toList();
        Map<UUID, List<WorkflowReviewImage>> imagesOf = images.findByReviewIdInOrderByReviewIdAscPosition(ids)
                .stream().collect(Collectors.groupingBy(WorkflowReviewImage::getReviewId));
        Set<UUID> markedByMe = new HashSet<>(helpful.findMarkedReviewIds(callerId, ids));
        Map<UUID, String> names = users.findNamesByIds(found.stream().map(WorkflowReview::getUserId)
                .collect(Collectors.toSet()));
        List<ReviewListItemView> data = found.stream()
                .map(review -> new ReviewListItemView(review.getId(), review.getRating(), review.getBody(),
                        ReviewViews.signed(mediaJobs, imagesOf.getOrDefault(review.getId(), List.of())),
                        review.getHelpfulCount(), review.getCreatedAt(), review.getUpdatedAt(),
                        markedByMe.contains(review.getId()), review.getUserId().equals(callerId),
                        names.get(review.getUserId())))
                .toList();
        return new ReviewPage(data, total, page, size);
    }

    /** Sets the caller's helpful mark on another member's review; repeating the value changes nothing. */
    @Transactional
    public HelpfulResult setHelpful(UUID callerId, UUID workflowId, UUID reviewId, boolean mark) {
        requireVisible(callerId, workflowId);
        WorkflowReview review = reviews.findByIdAndWorkflowId(reviewId, workflowId)
                .orElseThrow(WorkflowReviewService::reviewNotFound);
        if (review.getUserId().equals(callerId)) {
            throw forbidden(new LocalizedMessage("workflow.review.helpfulOwn"));
        }
        try {
            int changed = mark ? helpful.insertIfAbsent(reviewId, callerId, clock.instant())
                    : helpful.deleteMark(reviewId, callerId);
            if (changed > 0) {
                reviews.addHelpful(reviewId, mark ? changed : -changed);
            }
        } catch (DataIntegrityViolationException e) {
            // The review was deleted between the lookup and the insert.
            throw reviewNotFound();
        }
        int count = reviews.findByIdAndWorkflowId(reviewId, workflowId)
                .orElseThrow(WorkflowReviewService::reviewNotFound).getHelpfulCount();
        return new HelpfulResult(mark, count);
    }

    /** Stores the caller's report of another member's review; a repeat keeps the first. */
    @Transactional
    public void report(UUID callerId, UUID workflowId, UUID reviewId, String reason, String details) {
        requireVisible(callerId, workflowId);
        WorkflowReview review = reviews.findByIdAndWorkflowId(reviewId, workflowId)
                .orElseThrow(WorkflowReviewService::reviewNotFound);
        if (review.getUserId().equals(callerId)) {
            throw forbidden(new LocalizedMessage("workflow.review.reportOwn"));
        }
        ReviewRules.Report report = ReviewRules.report(reason, details);
        try {
            reports.insertIfAbsent(reviewId, callerId, report.reason().code(), report.details(), clock.instant());
        } catch (DataIntegrityViolationException e) {
            throw reviewNotFound();
        }
    }

    private Written persist(Workflow workflow, UUID callerId, int stars, String text, ReviewRules.ImagePlan plan,
            List<StoredAttachment> keptNow) {
        Instant now = clock.instant();
        WorkflowReview review = reviews.lockByWorkflowIdAndUserId(workflow.getId(), callerId).orElse(null);
        if (review == null) {
            requireMayCreate(workflow, callerId);
            reviews.insertIfAbsent(UUID.randomUUID(), workflow.getId(), callerId, stars, text, now);
            review = reviews.lockByWorkflowIdAndUserId(workflow.getId(), callerId)
                    .orElseThrow(WorkflowReviewService::reviewNotFound);
        }
        review.replace(stars, text, now);

        List<WorkflowReviewImage> locked = images.findByReviewIdOrderByPosition(review.getId());
        if (plan == null) {
            return new Written(review, locked, List.of());
        }
        Map<UUID, WorkflowReviewImage> lockedById = locked.stream()
                .collect(Collectors.toMap(WorkflowReviewImage::getId, Function.identity()));
        Map<UUID, StoredAttachment> uploaded = new HashMap<>();
        for (int i = 0; i < plan.uploadIds().size(); i++) {
            uploaded.put(plan.uploadIds().get(i), keptNow.get(i));
        }
        List<WorkflowReviewImage> ordered = new ArrayList<>();
        int position = 1;
        for (UUID id : plan.finalOrder()) {
            WorkflowReviewImage kept = lockedById.get(id);
            if (kept != null) {
                kept.moveTo(position);
                ordered.add(kept);
            } else if (uploaded.containsKey(id)) {
                StoredAttachment stored = uploaded.get(id);
                ordered.add(new WorkflowReviewImage(UUID.randomUUID(), review.getId(), position, stored.bucket(),
                        stored.objectKey(), stored.contentType(), stored.sizeBytes(), now));
            } else {
                // A kept id that a concurrent edit dropped after it was classified.
                throw imagesRefused();
            }
            position++;
        }
        Set<UUID> keptIds = new HashSet<>(plan.finalOrder());
        List<WorkflowReviewImage> dropped = locked.stream().filter(image -> !keptIds.contains(image.getId()))
                .toList();
        images.deleteAll(dropped);
        List<WorkflowReviewImage> saved = images.saveAll(ordered);
        return new Written(review, saved, dropped.stream().map(WorkflowReviewService::storedOf)
                .toList());
    }

    /** Copies and strips the new uploads outside any transaction; a refusal is a 422 on {@code images}. */
    private List<StoredAttachment> keepUploads(UUID callerId, UUID workflowId, List<UUID> uploadIds) {
        if (uploadIds.isEmpty()) {
            return List.of();
        }
        requireMediaOn();
        try {
            List<MediaAssetInput> inputs = mediaJobs.resolveImageUploads(UploadPurpose.REVIEW_IMAGE, callerId,
                    uploadIds);
            String prefix = "workflow-review/" + workflowId + "/" + callerId + "/" + UUID.randomUUID();
            return mediaJobs.keepAttachments(prefix, inputs, MAX_IMAGE_BYTES);
        } catch (UploadRejectedException e) {
            LOG.info("workflow.review.images outcome=rejected field={}", e.field());
            LocalizedMessage reason = e.detail();
            throw new UnprocessableEntityException(reason, null, Map.of("images", reason));
        }
    }

    /**
     * Never throws: it runs on a path that is already answering, and a copy left kept costs storage only.
     * {@code reviewId} is {@code null} when a first write failed before any review row existed.
     */
    private void release(List<StoredAttachment> stored, String outcome, UUID reviewId) {
        if (stored.isEmpty()) {
            return;
        }
        try {
            mediaJobs.releaseAttachments(stored);
            LOG.info("workflow.review.images outcome=released reason={} count={} reviewId={}", outcome, stored.size(),
                    reviewId);
        } catch (RuntimeException e) {
            LOG.warn("workflow.review.images outcome=release_failed reason={} count={} reviewId={} error={}", outcome,
                    stored.size(), reviewId, e.getClass().getSimpleName());
        }
    }

    private void requireMayCreate(Workflow workflow, UUID callerId) {
        if (workflow.getOwnerId().equals(callerId)) {
            throw forbidden(new LocalizedMessage("workflow.review.ownWorkflow"));
        }
        if (!runs.existsByWorkflowIdAndOwnerIdAndStatus(workflow.getId(), callerId, RunStatus.COMPLETED)) {
            throw forbidden(new LocalizedMessage("workflow.review.runRequired"));
        }
    }

    private void requireMediaOn() {
        if (!mediaJobs.isEnabled()) {
            throw new LocalizedStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    new LocalizedMessage("workflow.review.imagesUnavailable"));
        }
    }

    private Workflow requireVisible(UUID callerId, UUID workflowId) {
        return workflows.findVisibleTo(workflowId, callerId)
                .orElseThrow(() -> new NotFoundException(new LocalizedMessage("workflow.notFound", workflowId)));
    }

    private static StoredAttachment storedOf(WorkflowReviewImage image) {
        return new StoredAttachment(image.getBucket(), image.getObjectKey(), image.getContentType(),
                image.getSizeBytes());
    }

    private static LocalizedStatusException forbidden(LocalizedMessage detail) {
        return new LocalizedStatusException(HttpStatus.FORBIDDEN, detail);
    }

    private static NotFoundException reviewNotFound() {
        return new NotFoundException(new LocalizedMessage("workflow.review.notFound"));
    }

    private static UnprocessableEntityException imagesRefused() {
        LocalizedMessage reason = new LocalizedMessage("workflow.review.imagesInvalid");
        return new UnprocessableEntityException(reason, null, Map.of("images", reason));
    }
}
