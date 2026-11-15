package com.flowcortex.mail.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MailHtmlTest {

    @Test
    void markupInTheTextIsEscaped() {
        String html = MailHtml.paragraphs("<b>bold</b> & \"quoted\" it's");

        assertThat(html).contains("&lt;b&gt;bold&lt;/b&gt; &amp; &quot;quoted&quot; it&#39;s")
                .doesNotContain("<b>");
    }

    @Test
    void aUrlBecomesAnAnchorWithItsQueryEscaped() {
        String html = MailHtml.paragraphs("Open https://app.example/confirm-email?token=abc&x=1 now");

        assertThat(html).contains("<a href=\"https://app.example/confirm-email?token=abc&amp;x=1\"")
                .contains(">https://app.example/confirm-email?token=abc&amp;x=1</a> now");
    }

    @Test
    void trailingSentencePunctuationStaysOutOfTheLink() {
        String html = MailHtml.paragraphs("See (https://app.example/a). Or https://app.example/b, then https://app.example/c!?");

        assertThat(html).contains("href=\"https://app.example/a\"").contains("</a>). Or ")
                .contains("href=\"https://app.example/b\"").contains("</a>, then ")
                .contains("href=\"https://app.example/c\"").endsWith("</a>!?</p>");
    }

    @Test
    void blankLinesBecomeParagraphsAndSingleNewlinesBreaks() {
        String html = MailHtml.paragraphs("Hello,\n\nline one\nline two\r\n\r\n\r\nBye\n");

        assertThat(html.split("</p>")).hasSize(3);
        assertThat(html).contains(">Hello,</p>").contains("line one<br>line two").contains(">Bye</p>");
    }

    @Test
    void thePageFillsEverySlotOnceWithoutReinterpretingTheBody() {
        String page = MailHtml.page("vi", "Nodi <Co>", "<p>{{footer}}</p>", "<p>footer</p>");

        assertThat(page).contains("<html lang=\"vi\"").contains("Nodi &lt;Co&gt;")
                .contains("<p>{{footer}}</p>").contains("<p>footer</p>").doesNotContain("{{lang}}")
                .doesNotContain("{{brand}}").doesNotContain("{{body}}");
    }
}
