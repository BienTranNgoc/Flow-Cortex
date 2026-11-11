package com.flowcortex.settings.api;

/** Which layer decided a read. */
public enum SettingSource {
    /** An admin stored the value. */
    STORED,
    /** Nothing stored; the server configuration decided. */
    CONFIGURED,
    /** Nothing stored and nothing configured; the declared code default decided. */
    DEFAULT,
    /** The server configuration turned a switch off, whatever is stored. */
    VETOED,
    /** The stored value could not be read or parsed; the safe position was served. */
    DEGRADED
}
