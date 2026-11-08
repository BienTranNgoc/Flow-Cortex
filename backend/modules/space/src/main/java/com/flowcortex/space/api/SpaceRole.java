package com.flowcortex.space.api;

/** A member's role in a space; only {@link #MANAGER} is ever written today. */
public enum SpaceRole {
    MANAGER,
    EDITOR,
    VIEWER
}
