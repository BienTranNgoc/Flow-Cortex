package com.flowcortex.workflow.application;

import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.workflow.api.WorkflowView;
import com.flowcortex.workflow.domain.Workflow;
import com.flowcortex.workflow.infrastructure.BookmarkedWorkflowRow;
import com.flowcortex.workflow.infrastructure.WorkflowBookmarkRepository;
import com.flowcortex.workflow.infrastructure.WorkflowRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A member's saved workflows. Every workflow route first passes the visibility rule, so a hidden one is a 404. */
@Service
public class WorkflowBookmarkService {

    /** A saved workflow as the member sees it, with the time it was saved. */
    public record BookmarkedWorkflow(WorkflowView workflow, Instant bookmarkedAt) {
    }

    /** One page of saved workflows, newest saved first, and the number the member can currently see. */
    public record BookmarkPage(List<BookmarkedWorkflow> data, long total) {
    }

    private final WorkflowRepository workflows;
    private final WorkflowBookmarkRepository bookmarks;
    private final WorkflowServiceImpl views;
    private final Clock clock;

    WorkflowBookmarkService(WorkflowRepository workflows, WorkflowBookmarkRepository bookmarks,
            WorkflowServiceImpl views, Clock clock) {
        this.workflows = workflows;
        this.bookmarks = bookmarks;
        this.views = views;
        this.clock = clock;
    }

    /** Saves or unsaves the workflow; repeating the same value changes nothing. */
    @Transactional
    public boolean set(UUID callerId, UUID workflowId, boolean bookmarked) {
        requireVisible(callerId, workflowId);
        if (bookmarked) {
            bookmarks.insertIfAbsent(callerId, workflowId, clock.instant());
        } else {
            bookmarks.deleteByUserIdAndWorkflowId(callerId, workflowId);
        }
        return bookmarked;
    }

    @Transactional(readOnly = true)
    public boolean get(UUID callerId, UUID workflowId) {
        requireVisible(callerId, workflowId);
        return bookmarks.existsByUserIdAndWorkflowId(callerId, workflowId);
    }

    @Transactional(readOnly = true)
    public BookmarkPage page(UUID callerId, int page, int size) {
        List<BookmarkedWorkflowRow> rows = bookmarks.pageVisible(callerId, PageRequest.of(page, size));
        List<WorkflowView> mapped = views.toViews(rows.stream().map(BookmarkedWorkflowRow::workflow).toList());
        List<BookmarkedWorkflow> data = new java.util.ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            data.add(new BookmarkedWorkflow(mapped.get(i), rows.get(i).bookmarkedAt()));
        }
        return new BookmarkPage(List.copyOf(data), bookmarks.countVisible(callerId));
    }

    private Workflow requireVisible(UUID callerId, UUID workflowId) {
        return workflows.findVisibleTo(workflowId, callerId)
                .orElseThrow(() -> new NotFoundException(new LocalizedMessage("workflow.notFound", workflowId)));
    }
}
