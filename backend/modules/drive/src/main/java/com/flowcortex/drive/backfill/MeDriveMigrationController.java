package com.flowcortex.drive.backfill;

import com.flowcortex.drive.web.DriveCaller;
import com.flowcortex.drive.web.DriveCaller.Caller;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /api/v1/me/drive-migration}: when the caller's earlier results reached Memory; 503 while spaces are off. */
@RestController
@RequestMapping("/api/v1/me/drive-migration")
class MeDriveMigrationController {

    private final DriveCaller driveCaller;
    private final DriveBackfillRepository backfills;

    MeDriveMigrationController(DriveCaller driveCaller, DriveBackfillRepository backfills) {
        this.driveCaller = driveCaller;
        this.backfills = backfills;
    }

    @Operation(operationId = "getDriveMigration")
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<DriveMigrationResponse> get(@AuthenticationPrincipal Jwt jwt) {
        Caller caller = driveCaller.require(jwt);
        return ResponseEntity.ok(backfills.find(caller.spaceId()).map(DriveMigrationResponse::of)
                .orElseGet(DriveMigrationResponse::none));
    }
}
