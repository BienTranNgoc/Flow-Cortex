package com.flowcortex.workflow.application;

/**
 * Where a sharing request came from. {@code remoteAddr} is the full address, kept in memory only so an
 * anonymous reader can be hashed for the access-log dedupe; it is never stored and left out of
 * {@link #toString()}. {@code network} is the masked form that is stored.
 */
public record ShareOrigin(String network, String remoteAddr, String userAgent) {

    /** The longest user agent stored, in characters. */
    static final int MAX_USER_AGENT_CHARS = 512;

    public ShareOrigin {
        if (userAgent != null && userAgent.length() > MAX_USER_AGENT_CHARS) {
            userAgent = userAgent.substring(0, MAX_USER_AGENT_CHARS);
        }
    }

    public static ShareOrigin of(String remoteAddr, String userAgent) {
        return new ShareOrigin(NetworkMask.of(remoteAddr), remoteAddr, userAgent);
    }

    @Override
    public String toString() {
        return "ShareOrigin[network=" + network + "]";
    }
}
