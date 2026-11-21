package com.flowcortex.mail.links;

/**
 * The two URLs a care or promotional mail carries: the footer page and the mailbox one-click endpoint.
 */
public record UnsubscribeLink(String footerUrl, String oneClickUrl) {
}
