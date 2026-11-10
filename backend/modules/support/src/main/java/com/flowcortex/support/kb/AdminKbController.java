package com.flowcortex.support.kb;

import com.flowcortex.support.kb.KnowledgeBaseService.AdminArticle;
import com.flowcortex.support.kb.KnowledgeBaseService.AdminArticleList;
import com.flowcortex.support.kb.KnowledgeBaseService.AdminGroup;
import com.flowcortex.support.kb.KnowledgeBaseService.ArticleDraft;
import com.flowcortex.shared.security.authz.Permission;
import com.flowcortex.shared.security.authz.RequirePermission;
import com.flowcortex.shared.web.audit.AdminAudit;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/admin/support/kb} — the admin's authoring of articles and topic groups. Not
 * affected by the Help Center switch, so the knowledge base can be prepared before launch.
 */
@RestController
@RequestMapping("/api/v1/admin/support/kb")
class AdminKbController {

    private final KnowledgeBaseService knowledgeBase;

    AdminKbController(KnowledgeBaseService knowledgeBase) {
        this.knowledgeBase = knowledgeBase;
    }

    @Schema(name = "AdminSupportKbGroupRequest")
    record GroupRequest(@NotBlank @Size(max = 60) String name) {
    }

    @Schema(name = "AdminSupportKbGroupOrder")
    record GroupOrderRequest(@NotEmpty List<@NotNull UUID> ids) {
    }

    @Schema(name = "AdminSupportKbArticleRequest")
    record ArticleRequest(
            @NotNull UUID groupId,
            @NotBlank @Size(max = 80) @Pattern(regexp = "^[a-z0-9]+(-[a-z0-9]+)*$") String slug,
            @NotBlank @Size(max = 150) String title,
            @NotBlank @Size(max = 20000) String body,
            @Min(1) @Max(3) Integer featuredPosition) {

        ArticleDraft draft() {
            return new ArticleDraft(groupId, slug, title.strip(), body, featuredPosition);
        }
    }

    @Operation(operationId = "listAdminSupportKbGroups")
    @RequirePermission(Permission.CONTENT_HELP_TOPIC_VIEW)
    @GetMapping(value = "/groups", produces = MediaType.APPLICATION_JSON_VALUE)
    List<AdminGroup> groups() {
        return knowledgeBase.adminGroups();
    }

    // @ResponseStatus documents 201/204 for springdoc/orval only; the ResponseEntity sets the same status.
    @Operation(operationId = "createAdminSupportKbGroup")
    @ResponseStatus(HttpStatus.CREATED)
    @ApiResponse(responseCode = "201")
    @ApiResponse(responseCode = "400", description = "validation-failed (errors)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "group-name-taken",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @RequirePermission(Permission.CONTENT_HELP_TOPIC_MANAGE)
    @AdminAudit("support_kb_group.create")
    @PostMapping(value = "/groups", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<AdminGroup> createGroup(@Valid @RequestBody GroupRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(knowledgeBase.createGroup(body.name().strip()));
    }

    @Operation(operationId = "renameAdminSupportKbGroup")
    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "400", description = "validation-failed (errors)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "group-not-found",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "group-name-taken",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @RequirePermission(Permission.CONTENT_HELP_TOPIC_MANAGE)
    @AdminAudit("support_kb_group.rename")
    @PutMapping(value = "/groups/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    AdminGroup renameGroup(@PathVariable UUID id, @Valid @RequestBody GroupRequest body) {
        return knowledgeBase.renameGroup(id, body.name().strip());
    }

    @Operation(operationId = "reorderAdminSupportKbGroups")
    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "400", description = "validation-failed (errors), group-order-mismatch",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @RequirePermission(Permission.CONTENT_HELP_TOPIC_MANAGE)
    @AdminAudit(value = "support_kb_group.reorder", capture = "ids")
    @PutMapping(value = "/groups/order", produces = MediaType.APPLICATION_JSON_VALUE)
    List<AdminGroup> reorderGroups(@Valid @RequestBody GroupOrderRequest body) {
        return knowledgeBase.reorderGroups(body.ids());
    }

    @Operation(operationId = "deleteAdminSupportKbGroup")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @ApiResponse(responseCode = "204")
    @ApiResponse(responseCode = "404", description = "group-not-found",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "group-not-empty (articleCount)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @RequirePermission(Permission.CONTENT_HELP_TOPIC_MANAGE)
    @AdminAudit("support_kb_group.delete")
    @DeleteMapping("/groups/{id}")
    ResponseEntity<Void> deleteGroup(@PathVariable UUID id) {
        knowledgeBase.deleteGroup(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(operationId = "listAdminSupportKbArticles")
    @RequirePermission(Permission.CONTENT_HELP_ARTICLE_VIEW)
    @GetMapping(value = "/articles", produces = MediaType.APPLICATION_JSON_VALUE)
    AdminArticleList articles() {
        return knowledgeBase.adminArticles();
    }

    @Operation(operationId = "getAdminSupportKbArticle")
    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "404", description = "article-not-found",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @RequirePermission(Permission.CONTENT_HELP_ARTICLE_VIEW)
    @GetMapping(value = "/articles/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    AdminArticle article(@PathVariable UUID id) {
        return knowledgeBase.adminArticle(id);
    }

    @Operation(operationId = "createAdminSupportKbArticle")
    @ResponseStatus(HttpStatus.CREATED)
    @ApiResponse(responseCode = "201")
    @ApiResponse(responseCode = "400", description = "validation-failed (errors)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "group-not-found",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "slug-taken, featured-full, featured-position-taken",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @RequirePermission(Permission.CONTENT_HELP_ARTICLE_MANAGE)
    @AdminAudit("support_kb_article.create")
    @PostMapping(value = "/articles", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<AdminArticle> createArticle(@Valid @RequestBody ArticleRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(knowledgeBase.createArticle(body.draft()));
    }

    @Operation(operationId = "updateAdminSupportKbArticle")
    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "400", description = "validation-failed (errors)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "article-not-found, group-not-found",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "slug-taken, featured-full, featured-position-taken",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @RequirePermission(Permission.CONTENT_HELP_ARTICLE_MANAGE)
    @AdminAudit("support_kb_article.update")
    @PutMapping(value = "/articles/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    AdminArticle updateArticle(@PathVariable UUID id, @Valid @RequestBody ArticleRequest body) {
        return knowledgeBase.updateArticle(id, body.draft());
    }

    @Operation(operationId = "publishAdminSupportKbArticle")
    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "404", description = "article-not-found",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @RequirePermission(Permission.CONTENT_HELP_ARTICLE_MANAGE)
    @AdminAudit("support_kb_article.publish")
    @PostMapping(value = "/articles/{id}/publish", produces = MediaType.APPLICATION_JSON_VALUE)
    AdminArticle publish(@PathVariable UUID id) {
        return knowledgeBase.publish(id);
    }

    @Operation(operationId = "archiveAdminSupportKbArticle")
    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "404", description = "article-not-found",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @RequirePermission(Permission.CONTENT_HELP_ARTICLE_MANAGE)
    @AdminAudit("support_kb_article.archive")
    @PostMapping(value = "/articles/{id}/archive", produces = MediaType.APPLICATION_JSON_VALUE)
    AdminArticle archive(@PathVariable UUID id) {
        return knowledgeBase.archive(id);
    }
}
