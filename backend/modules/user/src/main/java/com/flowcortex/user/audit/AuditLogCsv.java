package com.flowcortex.user.audit;

import com.flowcortex.user.application.UserCsv;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;
import tools.jackson.databind.json.JsonMapper;

/** The audit export format: the users export's cell rule, one row per audit row, the change summary as compact JSON. */
final class AuditLogCsv {

    private static final String EOL = "\r\n";
    private static final DateTimeFormatter FILE_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmm").withZone(ZoneOffset.UTC);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private AuditLogCsv() {
    }

    static String header() {
        return "Time,Actor ID,Actor email,Actor name,Action,Resource type,Resource ID,Resource label,Status,"
                + "Error code,IP,User agent,Origin,Change summary" + EOL;
    }

    static String row(AuditLogRepository.Row row) {
        List<String> cells = List.of(
                UserCsv.cell(row.occurredAt().toString()),
                UserCsv.cell(row.actorUserId().toString()),
                UserCsv.cell(row.actorEmail()),
                UserCsv.cell(row.actorName()),
                UserCsv.cell(row.action()),
                UserCsv.cell(row.resourceType()),
                UserCsv.cell(row.resourceId()),
                UserCsv.cell(row.label()),
                UserCsv.cell(row.status()),
                UserCsv.cell(row.errorCode()),
                UserCsv.cell(row.ip()),
                UserCsv.cell(row.userAgent()),
                UserCsv.cell(row.origin()),
                UserCsv.cell(row.changeSummary() == null ? null : JSON.writeValueAsString(row.changeSummary())));
        return cells.stream().collect(Collectors.joining(",", "", EOL));
    }

    static String fileName(Instant now) {
        return "audit-log-" + FILE_STAMP.format(now) + ".csv";
    }
}
