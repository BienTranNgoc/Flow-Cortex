package com.flowcortex.app.clienterrors;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ClientErrorRedactionTest {

    @Test
    void idsQueryAndFragmentAreRemovedFromTheRoute() {
        assertThat(ClientErrorRedaction.route("/workflows/42/runs/0b9d1c1e-7f3a-4c55-9a1e-2f7c3b6d8e90?tab=log#x"))
                .isEqualTo("/workflows/:id/runs/:id");
    }

    @Test
    void anUppercaseUuidIsAnId() {
        assertThat(ClientErrorRedaction.route("/runs/0B9D1C1E-7F3A-4C55-9A1E-2F7C3B6D8E90"))
                .isEqualTo("/runs/:id");
    }

    @Test
    void aSegmentThatMerelyStartsWithALetterIsKept() {
        assertThat(ClientErrorRedaction.route("/users/u42")).isEqualTo("/users/u42");
    }

    @Test
    void theRootAndATrailingSlashSurvive() {
        assertThat(ClientErrorRedaction.route("/")).isEqualTo("/");
        assertThat(ClientErrorRedaction.route("/workflows/")).isEqualTo("/workflows/");
        assertThat(ClientErrorRedaction.route("/?next=/a/1")).isEqualTo("/");
    }

    @Test
    void anEmailInANameIsRedacted() {
        assertThat(ClientErrorRedaction.name("Error an@example.com")).isEqualTo("Error <redacted>");
        assertThat(ClientErrorRedaction.name("a@b.io and c.d+e@f.org")).isEqualTo("<redacted> and <redacted>");
    }

    @Test
    void anEmailInARouteSegmentIsRedactedWhetherOrNotItIsPercentEncoded() {
        assertThat(ClientErrorRedaction.route("/users/an@example.com/edit")).isEqualTo("/users/<redacted>/edit");
        assertThat(ClientErrorRedaction.route("/users/an%40example.com/edit")).isEqualTo("/users/<redacted>/edit");
    }

    @Test
    void aWholeReportIsRedacted() {
        ClientErrorReport redacted = ClientErrorRedaction.redact(new ClientErrorReport(
                ClientErrorReport.Kind.QUERY, ClientErrorReport.App.ADMIN, "Error an@example.com",
                "/workflows/42?x=1", "vi"));

        assertThat(redacted).isEqualTo(new ClientErrorReport(
                ClientErrorReport.Kind.QUERY, ClientErrorReport.App.ADMIN, "Error <redacted>",
                "/workflows/:id", "vi"));
    }
}
