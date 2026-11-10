package com.flowcortex.app.clienterrors;

import java.util.regex.Pattern;

/** Server-side scrub of a report before it is recorded, whatever the client already removed. */
final class ClientErrorRedaction {

    private static final String REDACTED = "<redacted>";
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}");
    private static final Pattern ENCODED_AT = Pattern.compile("%40", Pattern.CASE_INSENSITIVE);
    private static final Pattern IDENTIFIER_SEGMENT = Pattern.compile(
            "\\d+|[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private ClientErrorRedaction() {
    }

    static ClientErrorReport redact(ClientErrorReport report) {
        return new ClientErrorReport(report.kind(), report.app(), name(report.name()), route(report.route()),
                report.lang());
    }

    static String name(String name) {
        return EMAIL.matcher(name).replaceAll(REDACTED);
    }

    /** Drops query and fragment, redacts e-mail addresses, and turns UUID or all-digit segments into {@code :id}. */
    static String route(String route) {
        int cut = firstIndexOfAny(route, '?', '#');
        String path = cut < 0 ? route : route.substring(0, cut);
        path = EMAIL.matcher(ENCODED_AT.matcher(path).replaceAll("@")).replaceAll(REDACTED);
        String[] segments = path.split("/", -1);
        for (int i = 0; i < segments.length; i++) {
            if (IDENTIFIER_SEGMENT.matcher(segments[i]).matches()) {
                segments[i] = ":id";
            }
        }
        return String.join("/", segments);
    }

    private static int firstIndexOfAny(String value, char first, char second) {
        int a = value.indexOf(first);
        int b = value.indexOf(second);
        if (a < 0) {
            return b;
        }
        return b < 0 ? a : Math.min(a, b);
    }
}
