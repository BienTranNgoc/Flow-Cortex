package com.flowcortex.trend.taxonomy;

import com.flowcortex.shared.security.authz.Permission;
import com.flowcortex.shared.security.authz.RequirePermission;
import com.flowcortex.shared.web.audit.AdminAudit;
import com.flowcortex.trend.taxonomy.TaxonomyService.CategoryView;
import com.flowcortex.trend.taxonomy.TaxonomyService.TagView;
import com.flowcortex.trend.taxonomy.TaxonomyService.TaxonomyView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/admin/trend/taxonomy}: the admin's catalogue of trend categories and tags. Names arrive
 * trimmed by this class; the service owns every rule beyond field validation.
 */
@RestController
@RequestMapping("/api/v1/admin/trend/taxonomy")
class AdminTrendTaxonomyController {

    private static final int CATEGORY_NAME_MAX = 60;
    private static final int TAG_NAME_MAX = 30;

    private final TaxonomyService taxonomy;

    AdminTrendTaxonomyController(TaxonomyService taxonomy) {
        this.taxonomy = taxonomy;
    }

    @Schema(name = "AdminTrendCategoryCreateRequest")
    record CategoryCreateRequest(
            @NotBlank @Size(max = CATEGORY_NAME_MAX) String nameVi,
            @NotBlank @Size(max = CATEGORY_NAME_MAX) String nameEn) {
    }

    @Schema(name = "AdminTrendCategoryUpdateRequest")
    record CategoryUpdateRequest(
            @Size(max = CATEGORY_NAME_MAX) String nameVi,
            @Size(max = CATEGORY_NAME_MAX) String nameEn,
            Boolean visible) {
    }

    @Schema(name = "AdminTrendCategoryOrderRequest")
    record CategoryOrderRequest(@NotNull @Size(max = TaxonomyService.MAX_RECORDS) List<@NotBlank String> keys) {
    }

    @Schema(name = "AdminTrendTagCreateRequest")
    record TagCreateRequest(
            @NotBlank @Size(max = TAG_NAME_MAX) String nameVi,
            @NotBlank @Size(max = TAG_NAME_MAX) String nameEn) {
    }

    @Schema(name = "AdminTrendTagUpdateRequest")
    record TagUpdateRequest(
            @Size(max = TAG_NAME_MAX) String nameVi,
            @Size(max = TAG_NAME_MAX) String nameEn,
            Boolean visible) {
    }

    @Schema(name = "AdminTrendTagOrderRequest")
    record TagOrderRequest(@NotNull @Size(max = TaxonomyService.MAX_RECORDS) List<@NotNull UUID> ids) {
    }

    @Operation(operationId = "listAdminTrendTaxonomy")
    @RequirePermission(Permission.CONTENT_TREND_TEMPLATE_VIEW)
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    TaxonomyView list() {
        return taxonomy.list();
    }

    // @ResponseStatus documents 201/204 for springdoc/orval only; the ResponseEntity sets the same status.
    @Operation(operationId = "createAdminTrendCategory")
    @ResponseStatus(HttpStatus.CREATED)
    @ApiResponse(responseCode = "201")
    @ApiResponse(responseCode = "400", description = "validation-failed (errors)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "taxonomy-full",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", description = "category-key-exhausted (errors.nameVi)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @RequirePermission(Permission.CONTENT_TREND_TEMPLATE_MANAGE)
    @AdminAudit(value = "trend_category.create", resourceParam = "key", capture = {"nameVi", "nameEn"})
    @PostMapping(value = "/categories", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<CategoryView> createCategory(@Valid @RequestBody CategoryCreateRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(taxonomy.createCategory(body.nameVi().strip(), body.nameEn().strip()));
    }

    @Operation(operationId = "updateAdminTrendCategory")
    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "400", description = "validation-failed (errors)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "category-not-found",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @RequirePermission(Permission.CONTENT_TREND_TEMPLATE_MANAGE)
    @AdminAudit(value = "trend_category.update", resourceParam = "key", capture = {"nameVi", "nameEn", "visible"})
    @PatchMapping(value = "/categories/{key}", produces = MediaType.APPLICATION_JSON_VALUE)
    CategoryView updateCategory(@PathVariable String key, @Valid @RequestBody CategoryUpdateRequest body) {
        return taxonomy.updateCategory(key, strip(body.nameVi()), strip(body.nameEn()), body.visible());
    }

    @Operation(operationId = "reorderAdminTrendCategories")
    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "400", description = "validation-failed (errors)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "taxonomy-order-stale",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @RequirePermission(Permission.CONTENT_TREND_TEMPLATE_MANAGE)
    @AdminAudit(value = "trend_category.reorder", capture = "keys")
    @PutMapping(value = "/categories/order", produces = MediaType.APPLICATION_JSON_VALUE)
    List<CategoryView> reorderCategories(@Valid @RequestBody CategoryOrderRequest body) {
        return taxonomy.reorderCategories(body.keys());
    }

    @Operation(operationId = "deleteAdminTrendCategory")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @ApiResponse(responseCode = "204")
    @ApiResponse(responseCode = "404", description = "category-not-found",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "category-in-use (templateCount)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @RequirePermission(Permission.CONTENT_TREND_TEMPLATE_MANAGE)
    @AdminAudit(value = "trend_category.delete", resourceParam = "key")
    @DeleteMapping("/categories/{key}")
    ResponseEntity<Void> deleteCategory(@PathVariable String key) {
        taxonomy.deleteCategory(key);
        return ResponseEntity.noContent().build();
    }

    @Operation(operationId = "createAdminTrendTag")
    @ResponseStatus(HttpStatus.CREATED)
    @ApiResponse(responseCode = "201")
    @ApiResponse(responseCode = "400", description = "validation-failed (errors)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "taxonomy-full",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @RequirePermission(Permission.CONTENT_TREND_TEMPLATE_MANAGE)
    @AdminAudit(value = "trend_tag.create", capture = {"nameVi", "nameEn"})
    @PostMapping(value = "/tags", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<TagView> createTag(@Valid @RequestBody TagCreateRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(taxonomy.createTag(body.nameVi().strip(), body.nameEn().strip()));
    }

    @Operation(operationId = "updateAdminTrendTag")
    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "400", description = "validation-failed (errors)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "tag-not-found",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @RequirePermission(Permission.CONTENT_TREND_TEMPLATE_MANAGE)
    @AdminAudit(value = "trend_tag.update", capture = {"nameVi", "nameEn", "visible"})
    @PatchMapping(value = "/tags/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    TagView updateTag(@PathVariable UUID id, @Valid @RequestBody TagUpdateRequest body) {
        return taxonomy.updateTag(id, strip(body.nameVi()), strip(body.nameEn()), body.visible());
    }

    @Operation(operationId = "reorderAdminTrendTags")
    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "400", description = "validation-failed (errors)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "taxonomy-order-stale",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @RequirePermission(Permission.CONTENT_TREND_TEMPLATE_MANAGE)
    @AdminAudit(value = "trend_tag.reorder", capture = "ids")
    @PutMapping(value = "/tags/order", produces = MediaType.APPLICATION_JSON_VALUE)
    List<TagView> reorderTags(@Valid @RequestBody TagOrderRequest body) {
        return taxonomy.reorderTags(body.ids());
    }

    @Operation(operationId = "deleteAdminTrendTag")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @ApiResponse(responseCode = "204")
    @ApiResponse(responseCode = "404", description = "tag-not-found",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @RequirePermission(Permission.CONTENT_TREND_TEMPLATE_MANAGE)
    @AdminAudit("trend_tag.delete")
    @DeleteMapping("/tags/{id}")
    ResponseEntity<Void> deleteTag(@PathVariable UUID id) {
        taxonomy.deleteTag(id);
        return ResponseEntity.noContent().build();
    }

    private static String strip(String name) {
        return name == null ? null : name.strip();
    }
}
