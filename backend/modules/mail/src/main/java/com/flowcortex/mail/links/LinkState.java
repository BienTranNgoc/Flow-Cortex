package com.flowcortex.mail.links;

/** Which lists a member has left. */
public record LinkState(boolean careUnsubscribed, boolean marketingUnsubscribed) {

    /** A member with no link row has left nothing. */
    public static final LinkState NONE = new LinkState(false, false);

    public boolean unsubscribedFrom(LinkList list) {
        return switch (list) {
            case CARE -> careUnsubscribed;
            case MARKETING -> marketingUnsubscribed;
        };
    }
}
