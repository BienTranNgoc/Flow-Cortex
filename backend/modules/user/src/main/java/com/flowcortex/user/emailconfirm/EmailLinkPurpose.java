package com.flowcortex.user.emailconfirm;

/** What a stored one-time token is for; a token is only ever spent for its own purpose. */
public enum EmailLinkPurpose {
    /** The emailed link that confirms a self-registered account. */
    EMAIL_CONFIRM,
    /** An emailed link that signs in a confirmed account without a password, so it can set one. */
    PASSWORD_LINK,
    /** Handed to the browser that may set the account's first password; never emailed. */
    PASSWORD_SETUP
}
