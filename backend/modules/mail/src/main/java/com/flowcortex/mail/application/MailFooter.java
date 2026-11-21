package com.flowcortex.mail.application;

import com.flowcortex.mail.api.MailCategory;
import com.flowcortex.mail.links.UnsubscribeLink;
import java.util.Map;

/**
 * What a care or promotional mail adds for unsubscribing: an HTML footer anchor, a closing line of the text
 * part and the two one-click headers. A transactional mail adds nothing.
 */
record MailFooter(String html, String textSuffix, Map<String, String> headers) {

    static final MailFooter NONE = new MailFooter("", "", Map.of());

    static MailFooter of(MailCategory category, UnsubscribeLink link, String label) {
        if (category == MailCategory.TRANSACTIONAL || link == null) {
            return NONE;
        }
        String html = "<a href=\"" + MailHtml.escape(link.footerUrl()) + "\" style=\"color:#066058;\">"
                + MailHtml.escape(label) + "</a>";
        return new MailFooter(html, "\n\n" + label + ": " + link.footerUrl(), Map.of(
                "List-Unsubscribe", "<" + link.oneClickUrl() + ">",
                "List-Unsubscribe-Post", "List-Unsubscribe=One-Click"));
    }
}
