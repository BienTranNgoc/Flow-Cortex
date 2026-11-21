package com.flowcortex.mail.links;

/** Which surface carried an unsubscribe: the footer link's page or a mailbox's one-click request. */
public enum LinkSource {
    FOOTER_LINK("footer_link"),
    ONE_CLICK("one_click");

    private final String wire;

    LinkSource(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }
}
