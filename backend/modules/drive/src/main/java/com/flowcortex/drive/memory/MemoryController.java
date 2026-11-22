package com.flowcortex.drive.memory;

import com.flowcortex.drive.domain.MemoryFilter;
import com.flowcortex.drive.memory.MemoryResultRepository.Flag;
import com.flowcortex.drive.web.DriveCaller;
import com.flowcortex.drive.web.DriveCaller.Caller;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/drive/memory} — the caller's Memory: the final results of their completed runs. Identity and
 * space come from the token only; 503 while spaces are switched off. Pin and hide change the entry, never the run.
 */
@RestController
@RequestMapping("/api/v1/drive/memory")
class MemoryController {

    /** Keeps {@code page * pageSize} within an {@code int}, the offset type of the query. */
    private static final int MAX_PAGE = Integer.MAX_VALUE / MemoryFilter.PAGE_SIZE_MAX;

    private final DriveCaller driveCaller;
    private final MemoryService memoryService;
    private final Clock clock;

    MemoryController(DriveCaller driveCaller, MemoryService memoryService, Clock clock) {
        this.driveCaller = driveCaller;
        this.memoryService = memoryService;
        this.clock = clock;
    }

    @Operation(operationId = "listDriveMemory")
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<MemoryListResponse> list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) List<String> type,
            @RequestParam(required = false) String time,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) List<String> size,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "0") @Min(0) @Max(MAX_PAGE) int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        Caller caller = driveCaller.require(jwt);
        MemoryFilter.Parsed parsed = MemoryFilter.parse(q, type, time, from, to, size, sort, page, pageSize,
                clock.instant());
        if (parsed.filter() == null) {
            throw new MemoryRequestException(parsed.errors());
        }
        return ResponseEntity.ok(memoryService.list(caller.spaceId(), parsed.filter()));
    }

    @Operation(operationId = "getDriveMemoryText")
    @GetMapping(value = "/{id}/text", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<MemoryTextResponse> text(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        Caller caller = driveCaller.require(jwt);
        return ResponseEntity.ok(memoryService.text(caller.spaceId(), caller.userId(), id));
    }

    @Operation(operationId = "pinDriveMemory")
    @PostMapping("/{id}/pin")
    ResponseEntity<Void> pin(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return flag(jwt, id, Flag.PINNED, true);
    }

    @Operation(operationId = "unpinDriveMemory")
    @DeleteMapping("/{id}/pin")
    ResponseEntity<Void> unpin(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return flag(jwt, id, Flag.PINNED, false);
    }

    @Operation(operationId = "hideDriveMemory")
    @PostMapping("/{id}/hide")
    ResponseEntity<Void> hide(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return flag(jwt, id, Flag.HIDDEN, true);
    }

    private ResponseEntity<Void> flag(Jwt jwt, UUID id, Flag flag, boolean value) {
        memoryService.setFlag(driveCaller.require(jwt).spaceId(), id, flag, value);
        return ResponseEntity.noContent().build();
    }
}
