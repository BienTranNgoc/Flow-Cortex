package com.flowcortex.support.request;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** What the request routes answer with. */
final class RequestViews {

    private RequestViews() {
    }

    @Schema(name = "SupportRequestSummary")
    record Summary(
            @Schema(requiredMode = RequiredMode.REQUIRED) long number,
            @Schema(requiredMode = RequiredMode.REQUIRED,
                    allowableValues = {"account", "credit", "workflow", "technical", "other"}) String type,
            @Schema(requiredMode = RequiredMode.REQUIRED) String subject,
            @Schema(requiredMode = RequiredMode.REQUIRED,
                    allowableValues = {"new", "in_progress", "waiting_member", "resolved", "closed"}) String status,
            @Schema(requiredMode = RequiredMode.REQUIRED) boolean memberUnread,
            @Schema(requiredMode = RequiredMode.REQUIRED) Instant createdAt,
            @Schema(requiredMode = RequiredMode.REQUIRED) Instant lastActivityAt) {
    }

    /** A signed URL that shows the image until {@code expiresAt}. */
    @Schema(name = "SupportRequestAttachment")
    record Attachment(
            @Schema(requiredMode = RequiredMode.REQUIRED) int position,
            @Schema(requiredMode = RequiredMode.REQUIRED) String contentType,
            @Schema(requiredMode = RequiredMode.REQUIRED) long sizeBytes,
            @Schema(requiredMode = RequiredMode.REQUIRED) String url,
            @Schema(requiredMode = RequiredMode.REQUIRED) Instant expiresAt) {
    }

    @Schema(name = "SupportRequestMessage")
    record Message(
            @Schema(requiredMode = RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"member", "admin"}) String authorKind,
            @Schema(requiredMode = RequiredMode.REQUIRED) String body,
            @Schema(requiredMode = RequiredMode.REQUIRED) Instant createdAt,
            @Schema(requiredMode = RequiredMode.REQUIRED) List<Attachment> attachments) {
    }

    @Schema(name = "SupportRequestDetail")
    record Detail(
            @Schema(requiredMode = RequiredMode.REQUIRED) long number,
            @Schema(requiredMode = RequiredMode.REQUIRED,
                    allowableValues = {"account", "credit", "workflow", "technical", "other"}) String type,
            @Schema(requiredMode = RequiredMode.REQUIRED) String subject,
            @Schema(requiredMode = RequiredMode.REQUIRED,
                    allowableValues = {"new", "in_progress", "waiting_member", "resolved", "closed"}) String status,
            @Schema(allowableValues = {"cancelled", "confirmed", "auto", "admin"}) String closedReason,
            @Schema(requiredMode = RequiredMode.REQUIRED) boolean memberUnread,
            @Schema(requiredMode = RequiredMode.REQUIRED) Instant createdAt,
            @Schema(requiredMode = RequiredMode.REQUIRED) Instant lastActivityAt,
            Instant resolvedAt,
            @Schema(requiredMode = RequiredMode.REQUIRED) List<Message> messages) {
    }

    /** {@code email} is null and {@code deleted} true once the member's account is gone. */
    @Schema(name = "SupportRequester")
    record Requester(
            @Schema(requiredMode = RequiredMode.REQUIRED) UUID id,
            String email,
            @Schema(requiredMode = RequiredMode.REQUIRED) boolean deleted) {
    }

    /** {@code email} is null and {@code deleted} true once the assignee's account is gone. */
    @Schema(name = "SupportAssignee")
    record SupportAssignee(
            @Schema(requiredMode = RequiredMode.REQUIRED) UUID id,
            String email,
            @Schema(requiredMode = RequiredMode.REQUIRED) boolean deleted) {
    }

    @Schema(name = "SupportConversationMessage")
    record ConversationMessage(
            @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"user", "assistant"}) String role,
            @Schema(requiredMode = RequiredMode.REQUIRED) String text,
            @Schema(allowableValues = {"answered", "no_answer", "blocked"}) String outcome,
            @Schema(requiredMode = RequiredMode.REQUIRED) Instant createdAt) {
    }

    @Schema(name = "AdminSupportRequestSummary")
    record AdminSummary(
            @Schema(requiredMode = RequiredMode.REQUIRED) long number,
            @Schema(requiredMode = RequiredMode.REQUIRED,
                    allowableValues = {"account", "credit", "workflow", "technical", "other"}) String type,
            @Schema(requiredMode = RequiredMode.REQUIRED) String subject,
            @Schema(requiredMode = RequiredMode.REQUIRED,
                    allowableValues = {"new", "in_progress", "waiting_member", "resolved", "closed"}) String status,
            @Schema(requiredMode = RequiredMode.REQUIRED) Requester requester,
            SupportAssignee assignee,
            @Schema(requiredMode = RequiredMode.REQUIRED) Instant createdAt,
            @Schema(requiredMode = RequiredMode.REQUIRED) Instant lastActivityAt) {
    }

    @Schema(name = "AdminSupportRequestPage")
    record AdminPage(
            @Schema(requiredMode = RequiredMode.REQUIRED) List<AdminSummary> items,
            @Schema(requiredMode = RequiredMode.REQUIRED) int page,
            @Schema(requiredMode = RequiredMode.REQUIRED) int size,
            @Schema(requiredMode = RequiredMode.REQUIRED) long total) {
    }

    /**
     * {@code conversation} is null when the request links no assistant conversation, or the linked one was deleted
     * after 90 days; {@code conversationExpired} is true only in the second case. {@code allowedStatuses} are the
     * targets the status menu may offer.
     */
    @Schema(name = "AdminSupportRequestDetail")
    record AdminDetail(
            @Schema(requiredMode = RequiredMode.REQUIRED) long number,
            @Schema(requiredMode = RequiredMode.REQUIRED,
                    allowableValues = {"account", "credit", "workflow", "technical", "other"}) String type,
            @Schema(requiredMode = RequiredMode.REQUIRED) String subject,
            @Schema(requiredMode = RequiredMode.REQUIRED,
                    allowableValues = {"new", "in_progress", "waiting_member", "resolved", "closed"}) String status,
            @Schema(allowableValues = {"cancelled", "confirmed", "auto", "admin"}) String closedReason,
            @Schema(requiredMode = RequiredMode.REQUIRED) boolean memberUnread,
            @Schema(requiredMode = RequiredMode.REQUIRED) Instant createdAt,
            @Schema(requiredMode = RequiredMode.REQUIRED) Instant lastActivityAt,
            Instant resolvedAt,
            @Schema(requiredMode = RequiredMode.REQUIRED) Requester requester,
            SupportAssignee assignee,
            @Schema(requiredMode = RequiredMode.REQUIRED) List<Message> messages,
            List<ConversationMessage> conversation,
            @Schema(requiredMode = RequiredMode.REQUIRED) boolean conversationExpired,
            @ArraySchema(arraySchema = @Schema(requiredMode = RequiredMode.REQUIRED),
                    schema = @Schema(allowableValues = {"in_progress", "resolved", "closed"}))
            List<String> allowedStatuses) {
    }

    @Schema(name = "SupportFacetCount")
    record FacetCount(
            @Schema(requiredMode = RequiredMode.REQUIRED) String value,
            @Schema(requiredMode = RequiredMode.REQUIRED) long count) {
    }

    /** {@code email} is null when the assignee's account is gone. */
    @Schema(name = "SupportAssigneeCount")
    record AssigneeCount(
            @Schema(requiredMode = RequiredMode.REQUIRED) UUID id,
            String email,
            @Schema(requiredMode = RequiredMode.REQUIRED) long count) {
    }

    @Schema(name = "AdminSupportAssigneeFacet")
    record AssigneeFacet(
            @Schema(requiredMode = RequiredMode.REQUIRED) long unassigned,
            @Schema(requiredMode = RequiredMode.REQUIRED) long mine,
            @Schema(requiredMode = RequiredMode.REQUIRED) List<AssigneeCount> people) {
    }

    /**
     * Per option, how many requests the inbox would show with it selected and every other filter kept; a
     * dimension's own filter does not narrow its own counts.
     */
    @Schema(name = "AdminSupportRequestFacets")
    record AdminFacets(
            @Schema(requiredMode = RequiredMode.REQUIRED) List<FacetCount> status,
            @Schema(requiredMode = RequiredMode.REQUIRED) List<FacetCount> type,
            @Schema(requiredMode = RequiredMode.REQUIRED) List<FacetCount> created,
            @Schema(requiredMode = RequiredMode.REQUIRED) AssigneeFacet assignee) {
    }

    @Schema(name = "SupportBulkAssignFailure")
    record BulkAssignFailure(
            @Schema(requiredMode = RequiredMode.REQUIRED) long number,
            @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"not_found", "closed", "changed"})
            String reason) {
    }

    @Schema(name = "AdminSupportBulkAssignResult")
    record BulkAssignResult(
            @Schema(requiredMode = RequiredMode.REQUIRED) List<Long> assigned,
            @Schema(requiredMode = RequiredMode.REQUIRED) List<Long> unchanged,
            @Schema(requiredMode = RequiredMode.REQUIRED) List<BulkAssignFailure> failed) {
    }

    @Schema(name = "SupportCount")
    record Count(@Schema(requiredMode = RequiredMode.REQUIRED) long count) {
    }

    /** PUT the image to {@code url} with exactly {@code headers} and no {@code Authorization} header. */
    @Schema(name = "SupportUploadGrant")
    record UploadGrantView(
            @Schema(requiredMode = RequiredMode.REQUIRED) UUID uploadId,
            @Schema(requiredMode = RequiredMode.REQUIRED) String url,
            @Schema(requiredMode = RequiredMode.REQUIRED) String method,
            @Schema(requiredMode = RequiredMode.REQUIRED) java.util.Map<String, String> headers,
            @Schema(requiredMode = RequiredMode.REQUIRED) Instant expiresAt,
            @Schema(requiredMode = RequiredMode.REQUIRED) long maxBytes) {

        /** Without the URL: it grants the upload to whoever holds it until it expires. */
        @Override
        public String toString() {
            return "UploadGrantView[uploadId=" + uploadId + ", url=<redacted>, expiresAt=" + expiresAt + "]";
        }
    }
}
