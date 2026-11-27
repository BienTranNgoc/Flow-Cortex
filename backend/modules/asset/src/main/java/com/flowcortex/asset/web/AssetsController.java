package com.flowcortex.asset.web;

import com.flowcortex.asset.application.AssetLinks;
import com.flowcortex.asset.application.RunResults;
import com.flowcortex.asset.application.SpaceAssetLibrary;
import com.flowcortex.asset.web.AssetCaller.Caller;
import com.flowcortex.asset.web.AssetResponses.AssetGroupPageResponse;
import com.flowcortex.asset.web.AssetResponses.AssetItemResponse;
import com.flowcortex.asset.web.AssetResponses.AssetLinkResponse;
import com.flowcortex.asset.web.AssetResponses.AssetPageResponse;
import com.flowcortex.asset.web.AssetResponses.AssetTagListResponse;
import com.flowcortex.asset.web.AssetResponses.AssetUsageResponse;
import com.flowcortex.asset.web.AssetResponses.RunResultListResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/assets} — the library of the caller's session space. Identity and space come only from the
 * token; an asset of another space and an unknown id answer the same 404. Query parameters arrive raw as a
 * multi-map so a repeated single-value parameter can be refused instead of silently reduced to one value.
 */
@RestController
@RequestMapping("/api/v1/assets")
class AssetsController {

    private final AssetCaller caller;
    private final SpaceAssetLibrary library;
    private final AssetLinks links;
    private final RunResults runResults;

    AssetsController(AssetCaller caller, SpaceAssetLibrary library, AssetLinks links, RunResults runResults) {
        this.caller = caller;
        this.library = library;
        this.links = links;
        this.runResults = runResults;
    }

    @Operation(operationId = "listAssets")
    @Parameters({
            @Parameter(name = "kind", in = ParameterIn.QUERY,
                    description = "Comma-separated kinds: video, image, audio", schema = @Schema(type = "string")),
            @Parameter(name = "source", in = ParameterIn.QUERY,
                    description = "Comma-separated sources: run, upload", schema = @Schema(type = "string")),
            @Parameter(name = "group", in = ParameterIn.QUERY,
                    description = "A group key: workflow:<id>, upload or day:<YYYY-MM-DD>",
                    schema = @Schema(type = "string")),
            @Parameter(name = "runId", in = ParameterIn.QUERY, schema = @Schema(type = "string", format = "uuid")),
            @Parameter(name = "q", in = ParameterIn.QUERY, description = "Search in titles and tags, at most 200 characters",
                    schema = @Schema(type = "string")),
            @Parameter(name = "tag", in = ParameterIn.QUERY, schema = @Schema(type = "string")),
            @Parameter(name = "favorite", in = ParameterIn.QUERY, schema = @Schema(type = "boolean")),
            @Parameter(name = "time", in = ParameterIn.QUERY,
                    schema = @Schema(type = "string", allowableValues = {"all", "7d", "30d", "custom"})),
            @Parameter(name = "from", in = ParameterIn.QUERY, description = "First day, Asia/Ho_Chi_Minh; with time=custom",
                    schema = @Schema(type = "string", format = "date")),
            @Parameter(name = "to", in = ParameterIn.QUERY, description = "Last day, inclusive; with time=custom",
                    schema = @Schema(type = "string", format = "date")),
            @Parameter(name = "sort", in = ParameterIn.QUERY,
                    schema = @Schema(type = "string", allowableValues = {"newest", "oldest", "size_desc", "size_asc"})),
            @Parameter(name = "pageSize", in = ParameterIn.QUERY,
                    schema = @Schema(type = "integer", defaultValue = "48", minimum = "1", maximum = "100")),
            @Parameter(name = "cursor", in = ParameterIn.QUERY, description = "meta.nextCursor of the previous page",
                    schema = @Schema(type = "string")),
            @Parameter(name = "mark", in = ParameterIn.QUERY, description = "Snapshot instant of a walk",
                    schema = @Schema(type = "string", format = "date-time"))
    })
    @GetMapping(value = "", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<AssetPageResponse> list(@AuthenticationPrincipal Jwt jwt,
            @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> params) {
        Caller who = caller.require(jwt);
        return ResponseEntity.ok(AssetPageResponse.from(library.list(who.spaceId(), params)));
    }

    @Operation(operationId = "listAssetGroups")
    @Parameters({
            @Parameter(name = "groupBy", in = ParameterIn.QUERY,
                    schema = @Schema(type = "string", allowableValues = {"workflow", "day"})),
            @Parameter(name = "page", in = ParameterIn.QUERY,
                    schema = @Schema(type = "integer", defaultValue = "1", minimum = "1")),
            @Parameter(name = "pageSize", in = ParameterIn.QUERY,
                    schema = @Schema(type = "integer", defaultValue = "10", minimum = "1", maximum = "30")),
            @Parameter(name = "itemsPerGroup", in = ParameterIn.QUERY,
                    schema = @Schema(type = "integer", defaultValue = "4", minimum = "1", maximum = "12")),
            @Parameter(name = "kind", in = ParameterIn.QUERY,
                    description = "Comma-separated kinds: video, image, audio", schema = @Schema(type = "string")),
            @Parameter(name = "source", in = ParameterIn.QUERY,
                    description = "Comma-separated sources: run, upload", schema = @Schema(type = "string")),
            @Parameter(name = "runId", in = ParameterIn.QUERY, schema = @Schema(type = "string", format = "uuid")),
            @Parameter(name = "q", in = ParameterIn.QUERY, schema = @Schema(type = "string")),
            @Parameter(name = "tag", in = ParameterIn.QUERY, schema = @Schema(type = "string")),
            @Parameter(name = "favorite", in = ParameterIn.QUERY, schema = @Schema(type = "boolean")),
            @Parameter(name = "time", in = ParameterIn.QUERY,
                    schema = @Schema(type = "string", allowableValues = {"all", "7d", "30d", "custom"})),
            @Parameter(name = "from", in = ParameterIn.QUERY, schema = @Schema(type = "string", format = "date")),
            @Parameter(name = "to", in = ParameterIn.QUERY, schema = @Schema(type = "string", format = "date")),
            @Parameter(name = "sort", in = ParameterIn.QUERY,
                    description = "Order of the items inside each group",
                    schema = @Schema(type = "string", allowableValues = {"newest", "oldest", "size_desc", "size_asc"})),
            @Parameter(name = "mark", in = ParameterIn.QUERY,
                    description = "meta.mark of page 1, sent with every later page",
                    schema = @Schema(type = "string", format = "date-time"))
    })
    @GetMapping(value = "/groups", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<AssetGroupPageResponse> groups(@AuthenticationPrincipal Jwt jwt,
            @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> params) {
        Caller who = caller.require(jwt);
        return ResponseEntity.ok(AssetGroupPageResponse.from(library.groups(who.spaceId(), params)));
    }

    @Operation(operationId = "getAssetUsage")
    @GetMapping(value = "/usage", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<AssetUsageResponse> usage(@AuthenticationPrincipal Jwt jwt) {
        Caller who = caller.require(jwt);
        return ResponseEntity.ok(AssetUsageResponse.from(library.usage(who.spaceId())));
    }

    @Operation(operationId = "listAssetTags")
    @GetMapping(value = "/tags", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<AssetTagListResponse> tags(@AuthenticationPrincipal Jwt jwt) {
        Caller who = caller.require(jwt);
        return ResponseEntity.ok(AssetTagListResponse.from(library.tags(who.spaceId())));
    }

    /** What the given runs produced; the literal path wins over {@code /{id}}. */
    @Operation(operationId = "listRunResults")
    @Parameter(name = "runId", in = ParameterIn.QUERY, required = true,
            description = "1-100 distinct run ids; a repeated id answers once",
            array = @ArraySchema(schema = @Schema(type = "string", format = "uuid"), minItems = 1, maxItems = 100))
    @GetMapping(value = "/run-results", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<RunResultListResponse> runResults(@AuthenticationPrincipal Jwt jwt,
            @Parameter(hidden = true) @RequestParam(name = "runId", required = false) List<String> runIds) {
        Caller who = caller.require(jwt);
        return ResponseEntity.ok(RunResultListResponse.from(runResults.results(who.spaceId(), who.userId(), runIds)));
    }

    /** The path variable stays text: a malformed id answers the same 404 as an unknown one. */
    @Operation(operationId = "getAsset")
    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<AssetItemResponse> get(@AuthenticationPrincipal Jwt jwt,
            @Parameter(schema = @Schema(type = "string", format = "uuid")) @PathVariable String id) {
        Caller who = caller.require(jwt);
        return ResponseEntity.ok(AssetItemResponse.from(library.one(who.spaceId(), id)));
    }

    /** A link that saves the file under the asset's title; 410 when the file is gone, 503 when the store is down. */
    @Operation(operationId = "getAssetDownloadUrl")
    @GetMapping(value = "/{id}/download-url", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<AssetLinkResponse> downloadUrl(@AuthenticationPrincipal Jwt jwt,
            @Parameter(schema = @Schema(type = "string", format = "uuid")) @PathVariable String id) {
        Caller who = caller.require(jwt);
        return ResponseEntity.ok(AssetLinkResponse.from(links.downloadUrl(who.spaceId(), id)));
    }

    /** A link whose response is shown inline and can be read by byte range; the same refusals as the download link. */
    @Operation(operationId = "getAssetViewUrl")
    @GetMapping(value = "/{id}/view-url", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<AssetLinkResponse> viewUrl(@AuthenticationPrincipal Jwt jwt,
            @Parameter(schema = @Schema(type = "string", format = "uuid")) @PathVariable String id) {
        Caller who = caller.require(jwt);
        return ResponseEntity.ok(AssetLinkResponse.from(links.viewUrl(who.spaceId(), id)));
    }
}
