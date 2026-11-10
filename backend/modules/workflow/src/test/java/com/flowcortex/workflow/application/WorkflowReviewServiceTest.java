package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.flowcortex.media.api.MediaAssetInput;
import com.flowcortex.media.api.MediaDownload;
import com.flowcortex.media.api.MediaJobs;
import com.flowcortex.media.api.StoredAttachment;
import com.flowcortex.media.api.UploadPurpose;
import com.flowcortex.media.api.UploadRejectedException;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.LocalizedStatusException;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.shared.web.UnprocessableEntityException;
import com.flowcortex.user.api.UserDirectory;
import com.flowcortex.workflow.domain.RunStatus;
import com.flowcortex.workflow.domain.Workflow;
import com.flowcortex.workflow.domain.WorkflowReview;
import com.flowcortex.workflow.domain.WorkflowReviewImage;
import com.flowcortex.workflow.infrastructure.WorkflowRepository;
import com.flowcortex.workflow.infrastructure.WorkflowReviewHelpfulRepository;
import com.flowcortex.workflow.infrastructure.WorkflowReviewImageRepository;
import com.flowcortex.workflow.infrastructure.WorkflowReviewReportRepository;
import com.flowcortex.workflow.infrastructure.WorkflowReviewRepository;
import com.flowcortex.workflow.infrastructure.WorkflowRunRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

/** {@link WorkflowReviewService}: the write path, its image handling and its refusals. */
class WorkflowReviewServiceTest {

    private static final UUID ANA = UUID.randomUUID();
    private static final UUID BEN = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-11T08:00:00Z");
    private static final MediaDownload LINK = new MediaDownload("https://store/x", NOW.plusSeconds(900));

    private final WorkflowRepository workflows = mock(WorkflowRepository.class);
    private final WorkflowRunRepository runs = mock(WorkflowRunRepository.class);
    private final WorkflowReviewRepository reviews = mock(WorkflowReviewRepository.class);
    private final WorkflowReviewImageRepository images = mock(WorkflowReviewImageRepository.class);
    private final WorkflowReviewHelpfulRepository helpful = mock(WorkflowReviewHelpfulRepository.class);
    private final WorkflowReviewReportRepository reports = mock(WorkflowReviewReportRepository.class);
    private final MediaJobs mediaJobs = mock(MediaJobs.class);
    private final UserDirectory users = mock(UserDirectory.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final WorkflowReviewService service = new WorkflowReviewService(workflows, runs, reviews, images,
            helpful, reports, mediaJobs, users, transactions, Clock.fixed(NOW, ZoneOffset.UTC));

    private final Workflow workflow = Workflow.draft(UUID.randomUUID(), ANA, "Dubbing");

    WorkflowReviewServiceTest() {
        when(transactions.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        when(mediaJobs.presignedGet(anyString(), anyString(), anyString())).thenReturn(LINK);
        when(mediaJobs.isEnabled()).thenReturn(true);
        when(workflows.findVisibleTo(workflow.getId(), BEN)).thenReturn(Optional.of(workflow));
        when(images.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private WorkflowReview existingReview() {
        WorkflowReview review = WorkflowReview.create(UUID.randomUUID(), workflow.getId(), BEN, 3, "ok", NOW);
        when(reviews.findByWorkflowIdAndUserId(workflow.getId(), BEN)).thenReturn(Optional.of(review));
        when(reviews.lockByWorkflowIdAndUserId(workflow.getId(), BEN)).thenReturn(Optional.of(review));
        return review;
    }

    private WorkflowReviewImage image(WorkflowReview review, int position) {
        return new WorkflowReviewImage(UUID.randomUUID(), review.getId(), position, "flowcortex-media",
                "workflow-review/w/u/b/" + position, "image/png", 100, NOW);
    }

    /** A first review: nothing stored yet, the insert succeeds and the locked re-read finds the new row. */
    private void firstReviewWillBeInserted() {
        when(reviews.findByWorkflowIdAndUserId(workflow.getId(), BEN)).thenReturn(Optional.empty());
        when(runs.existsByWorkflowIdAndOwnerIdAndStatus(workflow.getId(), BEN, RunStatus.COMPLETED))
                .thenReturn(true);
        when(reviews.insertIfAbsent(any(), eq(workflow.getId()), eq(BEN), anyInt(), any(), any())).thenReturn(1);
        AtomicInteger locks = new AtomicInteger();
        when(reviews.lockByWorkflowIdAndUserId(workflow.getId(), BEN)).thenAnswer(invocation ->
                locks.getAndIncrement() % 2 == 0
                        ? Optional.empty()
                        : Optional.of(WorkflowReview.create(UUID.randomUUID(), workflow.getId(), BEN, 4, null, NOW)));
    }

    private static void assertForbidden(Throwable thrown, String key) {
        assertThat(thrown).isInstanceOfSatisfying(LocalizedStatusException.class, e -> {
            assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(e.getDetailMessageCode()).isEqualTo(key);
        });
    }

    @Test
    void aMemberWithoutACompletedRunCannotCreate() {
        when(reviews.findByWorkflowIdAndUserId(workflow.getId(), BEN)).thenReturn(Optional.empty());
        when(runs.existsByWorkflowIdAndOwnerIdAndStatus(workflow.getId(), BEN, RunStatus.COMPLETED))
                .thenReturn(false);

        assertThatThrownBy(() -> service.write(BEN, workflow.getId(), 4, "good", null))
                .satisfies(thrown -> assertForbidden(thrown, "workflow.review.runRequired"));

        verify(reviews, never()).insertIfAbsent(any(), any(), any(), anyInt(), any(), any());
        verifyNoInteractions(mediaJobs);
    }

    @Test
    void theOwnerCannotCreate() {
        when(workflows.findVisibleTo(workflow.getId(), ANA)).thenReturn(Optional.of(workflow));
        when(reviews.findByWorkflowIdAndUserId(workflow.getId(), ANA)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.write(ANA, workflow.getId(), 5, null, null))
                .satisfies(thrown -> assertForbidden(thrown, "workflow.review.ownWorkflow"));

        verify(reviews, never()).insertIfAbsent(any(), any(), any(), anyInt(), any(), any());
    }

    @Test
    void replacingDoesNotRecheckTheRunGate() {
        WorkflowReview review = existingReview();
        when(images.findByReviewIdOrderByPosition(review.getId())).thenReturn(List.of());

        ReviewViews.ReviewView view = service.write(BEN, workflow.getId(), 5, null, null);

        assertThat(view.id()).isEqualTo(review.getId());
        assertThat(view.rating()).isEqualTo(5);
        assertThat(view.body()).isNull();
        assertThat(view.createdAt()).isEqualTo(NOW);
        verifyNoInteractions(runs);
    }

    @Test
    void anInvisibleWorkflowIsNotFoundBeforeAnyWrite() {
        UUID hidden = UUID.randomUUID();
        when(workflows.findVisibleTo(hidden, BEN)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.write(BEN, hidden, 5, "x", List.of(UUID.randomUUID())))
                .isInstanceOf(NotFoundException.class);

        verifyNoInteractions(reviews, runs, images, mediaJobs, transactions);
    }

    @Test
    void markingOrReportingOnesOwnReviewIsForbidden() {
        WorkflowReview review = existingReview();
        when(reviews.lockByIdAndWorkflowId(review.getId(), workflow.getId())).thenReturn(Optional.of(review));

        assertThatThrownBy(() -> service.setHelpful(BEN, workflow.getId(), review.getId(), true))
                .satisfies(thrown -> assertForbidden(thrown, "workflow.review.helpfulOwn"));
        assertThatThrownBy(() -> service.report(BEN, workflow.getId(), review.getId(), "spam", null))
                .satisfies(thrown -> assertForbidden(thrown, "workflow.review.reportOwn"));

        verifyNoInteractions(helpful, reports);
        verify(reviews, never()).addHelpful(any(), anyInt());
    }

    @Test
    void newUploadsAreKeptOutsideTheTransactionUnderAFreshBatchPrefix() {
        firstReviewWillBeInserted();
        UUID upload = UUID.randomUUID();
        List<MediaAssetInput> assets = List.of(new MediaAssetInput(UUID.randomUUID(), null));
        StoredAttachment stored = new StoredAttachment("flowcortex-media", "workflow-review/k/1", "image/png", 90);
        when(mediaJobs.resolveImageUploads(UploadPurpose.REVIEW_IMAGE, BEN, List.of(upload))).thenReturn(assets);
        when(mediaJobs.keepAttachments(startsWith("workflow-review/" + workflow.getId() + "/" + BEN + "/"),
                eq(assets), eq(5_242_880L))).thenReturn(List.of(stored));

        ReviewViews.ReviewView view = service.write(BEN, workflow.getId(), 4, null, List.of(upload));

        InOrder order = inOrder(mediaJobs, transactions);
        order.verify(mediaJobs).keepAttachments(anyString(), eq(assets), anyLong());
        order.verify(transactions).getTransaction(any());
        order.verify(transactions).commit(any());
        assertThat(view.images()).hasSize(1);
        ArgumentCaptor<String> prefix = ArgumentCaptor.forClass(String.class);
        service.write(BEN, workflow.getId(), 4, null, List.of(upload));
        verify(mediaJobs, org.mockito.Mockito.times(2)).keepAttachments(prefix.capture(), any(), anyLong());
        assertThat(prefix.getAllValues().get(0)).isNotEqualTo(prefix.getAllValues().get(1));
    }

    @Test
    void aRefusedUploadIsAnImagesRefusalAndNothingIsWritten() {
        firstReviewWillBeInserted();
        UUID upload = UUID.randomUUID();
        when(mediaJobs.resolveImageUploads(UploadPurpose.REVIEW_IMAGE, BEN, List.of(upload)))
                .thenThrow(new UploadRejectedException("uploadIds", new LocalizedMessage("upload.imageNotImage")));

        assertThatThrownBy(() -> service.write(BEN, workflow.getId(), 4, null, List.of(upload)))
                .isInstanceOfSatisfying(UnprocessableEntityException.class,
                        e -> assertThat(e.getErrors()).containsOnlyKeys("images"));

        verify(reviews, never()).insertIfAbsent(any(), any(), any(), anyInt(), any(), any());
        verify(transactions, never()).getTransaction(any());
        verify(mediaJobs, never()).releaseAttachments(any());
    }

    @Test
    void mediaOffWithANewUploadIsUnavailable() {
        firstReviewWillBeInserted();
        when(mediaJobs.isEnabled()).thenReturn(false);

        assertThatThrownBy(() -> service.write(BEN, workflow.getId(), 4, null, List.of(UUID.randomUUID())))
                .isInstanceOfSatisfying(LocalizedStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(e.getDetailMessageCode()).isEqualTo("workflow.review.imagesUnavailable");
                });

        verify(mediaJobs, never()).keepAttachments(anyString(), any(), anyLong());
    }

    @Test
    void droppedImagesAreReleasedAfterCommit() {
        WorkflowReview review = existingReview();
        WorkflowReviewImage keep = image(review, 1);
        WorkflowReviewImage drop = image(review, 2);
        when(images.findByReviewIdOrderByPosition(review.getId())).thenReturn(List.of(keep, drop));

        ReviewViews.ReviewView view = service.write(BEN, workflow.getId(), 4, "ok", List.of(keep.getId()));

        assertThat(view.images()).extracting(ReviewViews.ReviewImageView::id).containsExactly(keep.getId());
        InOrder order = inOrder(transactions, mediaJobs);
        order.verify(transactions).commit(any());
        order.verify(mediaJobs).releaseAttachments(List.of(
                new StoredAttachment("flowcortex-media", drop.getObjectKey(), "image/png", 100)));
        verify(images).deleteAll(List.of(drop));
    }

    @Test
    void keptCopiesAreReleasedWhenTheWriteFails() {
        firstReviewWillBeInserted();
        UUID upload = UUID.randomUUID();
        List<MediaAssetInput> assets = List.of(new MediaAssetInput(UUID.randomUUID(), null));
        StoredAttachment stored = new StoredAttachment("flowcortex-media", "workflow-review/k/1", "image/png", 90);
        when(mediaJobs.resolveImageUploads(UploadPurpose.REVIEW_IMAGE, BEN, List.of(upload))).thenReturn(assets);
        when(mediaJobs.keepAttachments(anyString(), eq(assets), anyLong())).thenReturn(List.of(stored));
        when(images.saveAll(anyList())).thenThrow(new IllegalStateException("db down"));

        assertThatThrownBy(() -> service.write(BEN, workflow.getId(), 4, null, List.of(upload)))
                .isInstanceOf(IllegalStateException.class);

        verify(transactions).rollback(any());
        verify(mediaJobs).releaseAttachments(List.of(stored));
    }

    @Test
    void aReleaseFailureIsLoggedNotThrown() {
        WorkflowReview review = existingReview();
        WorkflowReviewImage drop = image(review, 1);
        when(images.findByReviewIdOrderByPosition(review.getId())).thenReturn(List.of(drop));
        doThrow(new IllegalStateException("store down")).when(mediaJobs).releaseAttachments(anyList());

        Logger logger = (Logger) LoggerFactory.getLogger(WorkflowReviewService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            ReviewViews.ReviewView view = service.write(BEN, workflow.getId(), 4, null, List.of());

            assertThat(view.images()).isEmpty();
            verify(mediaJobs).releaseAttachments(anyList());
            assertThat(appender.list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage())
                        .contains("outcome=release_failed")
                        .contains("reviewId=" + review.getId());
            });
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void deletingAReviewReleasesItsImagesAfterCommit() {
        WorkflowReview review = existingReview();
        WorkflowReviewImage stored = image(review, 1);
        when(images.findByReviewIdOrderByPosition(review.getId())).thenReturn(List.of(stored));

        service.deleteMine(BEN, workflow.getId());

        InOrder order = inOrder(reviews, transactions, mediaJobs);
        order.verify(reviews).delete(review);
        order.verify(transactions).commit(any());
        order.verify(mediaJobs).releaseAttachments(List.of(new StoredAttachment(stored.getBucket(),
                stored.getObjectKey(), stored.getContentType(), stored.getSizeBytes())));
    }

    @Test
    void deletingWithoutAReviewIsNotFoundAndReleasesNothing() {
        when(reviews.lockByWorkflowIdAndUserId(workflow.getId(), BEN)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteMine(BEN, workflow.getId()))
                .isInstanceOfSatisfying(NotFoundException.class,
                        e -> assertThat(e.getDetail().code()).isEqualTo("workflow.review.notFound"));

        verify(transactions).rollback(any());
        verify(reviews, never()).delete(any());
        verify(mediaJobs, never()).releaseAttachments(anyList());
    }

    @Test
    void aKeptIdDroppedByAConcurrentEditIsAnImagesRefusal() {
        WorkflowReview review = existingReview();
        WorkflowReviewImage keep = image(review, 1);
        when(images.findByReviewIdOrderByPosition(review.getId())).thenReturn(List.of(keep), List.of());

        assertThatThrownBy(() -> service.write(BEN, workflow.getId(), 4, null, List.of(keep.getId())))
                .isInstanceOfSatisfying(UnprocessableEntityException.class,
                        e -> assertThat(e.getErrors()).containsOnlyKeys("images"));

        verify(transactions).rollback(any());
        verify(images, never()).saveAll(anyList());
    }
}
