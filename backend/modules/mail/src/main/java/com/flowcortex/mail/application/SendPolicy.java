package com.flowcortex.mail.application;

import com.flowcortex.mail.api.MailCategory;
import com.flowcortex.mail.links.LinkList;
import com.flowcortex.mail.links.LinkState;

/** Whether a mail of a category may go to a member given the lists the member has left. */
final class SendPolicy {

    enum Verdict { SEND, SUPPRESS }

    private SendPolicy() {
    }

    /** A mail is held back only by the list its own category belongs to; transactional mail has none. */
    static Verdict decide(MailCategory category, LinkState state) {
        return LinkList.of(category).filter(state::unsubscribedFrom).isPresent() ? Verdict.SUPPRESS : Verdict.SEND;
    }
}
