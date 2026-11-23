package com.flowcortex.user.application;

import com.flowcortex.user.api.UserView;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

/** The users export format: every cell quoted, formula triggers neutralised, rows ended by CRLF. */
public final class UserCsv {

    /** Lets spreadsheet apps that guess the file encoding read Vietnamese names as UTF-8. */
    public static final String BOM = "﻿";

    private static final String EOL = "\r\n";
    private static final String FORMULA_TRIGGERS = "=+-@\t\r";
    private static final DateTimeFormatter FILE_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmm").withZone(ZoneOffset.UTC);

    private UserCsv() {
    }

    public static String header() {
        return "ID,Name,Email,Username,Role,Status,State,Email confirmed,Sign-in methods,Created" + EOL;
    }

    public static String row(UserView user) {
        List<String> cells = List.of(
                cell(user.id().toString()),
                cell(user.name()),
                cell(user.email()),
                cell(user.username()),
                cell(user.role()),
                cell(user.status()),
                cell(user.state()),
                cell(iso(user.emailConfirmedAt())),
                cell(String.join("+", user.signInMethods())),
                cell(iso(user.createdAt())));
        return cells.stream().collect(Collectors.joining(",", "", EOL));
    }

    /** Quotes per RFC 4180; text a spreadsheet would run as a formula gets a leading apostrophe. */
    public static String cell(String value) {
        String text = value == null ? "" : value;
        if (!text.isEmpty() && FORMULA_TRIGGERS.indexOf(text.charAt(0)) >= 0) {
            text = "'" + text;
        }
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }

    public static String fileName(Instant now) {
        return "users-" + FILE_STAMP.format(now) + ".csv";
    }

    private static String iso(Instant instant) {
        return instant == null ? null : instant.toString();
    }
}
