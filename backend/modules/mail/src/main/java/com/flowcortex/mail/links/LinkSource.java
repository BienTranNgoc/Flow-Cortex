package com.flowcortex.mail.links;

/** Which surface carried a list change: the footer link's page, a mailbox's one-click request or the member's own switch. */
public enum LinkSource {
    FOOTER_LINK("footer_link"),
    ONE_CLICK("one_click"),
    PREFERENCES("preferences");

    private final String wire;

    LinkSource(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }
}
