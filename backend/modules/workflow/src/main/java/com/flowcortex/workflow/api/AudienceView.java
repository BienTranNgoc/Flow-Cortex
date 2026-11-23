package com.flowcortex.workflow.api;

/**
 * Who a workflow is served to, with its definition. {@code workflow} and {@code startKind} are
 * {@code null} only for {@link Audience#NOT_FOUND}. {@code startKind} is what a member must give to start a run.
 */
public record AudienceView(Audience audience, WorkflowView workflow, StartKind startKind) {

    /** Who may see the workflow: {@code ALL_MEMBERS} is shared and published, as another member is served it. */
    public enum Audience {
        NOT_FOUND, DELETED, DRAFT, OWNER_ONLY, ALL_MEMBERS
    }

    /** The input a run of the workflow starts from. */
    public enum StartKind {
        TEXT, UPLOAD, LINK
    }
}
