package com.flowcortex.drive.backfill;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;

/** When the caller's earlier results were brought into Memory; both fields are null when nothing was. */
record DriveMigrationResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true) Instant migratedAt,
        @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true) Counts counts) {

    /** What the migration took in. */
    record Counts(@Schema(requiredMode = RequiredMode.REQUIRED) int runs,
            @Schema(requiredMode = RequiredMode.REQUIRED) int results) {
    }

    static DriveMigrationResponse of(DriveBackfillRepository.Record record) {
        return new DriveMigrationResponse(record.migratedAt(), new Counts(record.runs(), record.results()));
    }

    static DriveMigrationResponse none() {
        return new DriveMigrationResponse(null, null);
    }
}
