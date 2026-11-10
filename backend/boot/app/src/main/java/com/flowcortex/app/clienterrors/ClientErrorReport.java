package com.flowcortex.app.clienterrors;

/** One accepted browser error report; {@code lang} is null when the client did not send one. */
record ClientErrorReport(Kind kind, App app, String name, String route, String lang) {

    enum Kind {
        RENDER("render"),
        QUERY("query");

        private final String wire;

        Kind(String wire) {
            this.wire = wire;
        }

        String wire() {
            return wire;
        }

        static Kind fromWire(String value) {
            for (Kind kind : values()) {
                if (kind.wire.equals(value)) {
                    return kind;
                }
            }
            return null;
        }
    }

    enum App {
        MEMBER("member"),
        ADMIN("admin");

        private final String wire;

        App(String wire) {
            this.wire = wire;
        }

        String wire() {
            return wire;
        }

        static App fromWire(String value) {
            for (App app : values()) {
                if (app.wire.equals(value)) {
                    return app;
                }
            }
            return null;
        }
    }
}
