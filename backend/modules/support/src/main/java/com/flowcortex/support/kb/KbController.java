package com.flowcortex.support.kb;

import com.flowcortex.support.kb.KnowledgeBaseService.FeaturedArticle;
import com.flowcortex.support.kb.KnowledgeBaseService.PublishedArticle;
import com.flowcortex.support.kb.KnowledgeBaseService.PublishedGroup;
import com.flowcortex.support.kb.KnowledgeBaseService.SearchHit;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code /api/v1/support/kb} — published articles for anyone, signed in or not. */
@RestController
@RequestMapping("/api/v1/support/kb")
@SecurityRequirements
@ApiResponse(responseCode = "503", description = "support-disabled",
        content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetail.class)))
class KbController {

    private static final int MAX_SEARCH_RESULTS = 10;

    private final KnowledgeBaseService knowledgeBase;

    KbController(KnowledgeBaseService knowledgeBase) {
        this.knowledgeBase = knowledgeBase;
    }

    @Operation(operationId = "listSupportKbGroups")
    @ApiResponse(responseCode = "200")
    @GetMapping(value = "/groups", produces = MediaType.APPLICATION_JSON_VALUE)
    List<PublishedGroup> groups() {
        return knowledgeBase.publishedGroups();
    }

    @Operation(operationId = "listSupportKbFeatured")
    @ApiResponse(responseCode = "200")
    @GetMapping(value = "/featured", produces = MediaType.APPLICATION_JSON_VALUE)
    List<FeaturedArticle> featured() {
        return knowledgeBase.featured();
    }

    @Operation(operationId = "getSupportKbArticle")
    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "404", description = "article-not-found",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping(value = "/articles/{slug}", produces = MediaType.APPLICATION_JSON_VALUE)
    PublishedArticle article(@PathVariable String slug) {
        return knowledgeBase.publishedArticle(slug);
    }

    @Operation(operationId = "searchSupportKb")
    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "400", description = "validation-failed (errors.q)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping(value = "/search", produces = MediaType.APPLICATION_JSON_VALUE)
    List<SearchHit> search(@RequestParam @Size(min = 2, max = 200) String q) {
        return knowledgeBase.searchHits(q, MAX_SEARCH_RESULTS);
    }
}
