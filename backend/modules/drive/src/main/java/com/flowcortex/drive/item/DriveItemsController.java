package com.flowcortex.drive.item;

import com.flowcortex.drive.web.DriveCaller;
import com.flowcortex.drive.web.DriveCaller.Caller;
import com.flowcortex.shared.web.PageView;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/drive/items} — the items of the caller's personal space. Identity and space come only from
 * the token; an item of another space and an unknown id answer the same 404.
 */
@RestController
@RequestMapping("/api/v1/drive/items")
class DriveItemsController {

    private static final int MAX_PAGE_SIZE = 100;
    /** Keeps {@code page * size} within an {@code int}, the offset type the list pages with. */
    private static final int MAX_PAGE = Integer.MAX_VALUE / MAX_PAGE_SIZE;

    private final DriveCaller driveCaller;
    private final DriveItemService service;

    DriveItemsController(DriveCaller driveCaller, DriveItemService service) {
        this.driveCaller = driveCaller;
        this.service = service;
    }

    @Operation(operationId = "listDriveItems")
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<PageView<DriveItemResponse>> list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") @Min(0) @Max(MAX_PAGE) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String contentType) {
        Caller caller = driveCaller.require(jwt);
        return ResponseEntity.ok(service.list(caller.spaceId(), role, contentType, page, size)
                .map(row -> DriveItemResponse.from(service.view(row))));
    }

    @Operation(operationId = "createDriveItem")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<DriveItemResponse> create(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateDriveItemRequest request) {
        Caller caller = driveCaller.require(jwt);
        DriveItemRow created = service.create(caller, request.toCommand());
        return ResponseEntity.created(URI.create("/api/v1/drive/items/" + created.id()))
                .body(DriveItemResponse.from(service.view(created)));
    }

    @Operation(operationId = "getDriveItem")
    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<DriveItemResponse> get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        Caller caller = driveCaller.require(jwt);
        return ResponseEntity.ok(DriveItemResponse.from(service.view(service.get(caller, id))));
    }

    @Operation(operationId = "updateDriveItem")
    @PatchMapping(value = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<DriveItemResponse> update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @Valid @RequestBody UpdateDriveItemRequest request) {
        Caller caller = driveCaller.require(jwt);
        return ResponseEntity.ok(DriveItemResponse.from(service.view(service.update(caller, id, request.toPatch()))));
    }

    @Operation(operationId = "deleteDriveItem")
    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        Caller caller = driveCaller.require(jwt);
        service.delete(caller, id);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }
}
