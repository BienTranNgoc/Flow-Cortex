package com.flowcortex.user.api;

import java.util.UUID;

/**
 * A module's reason an account cannot be deleted yet. Beans of this type are collected by the user module;
 * the key, order and messages are only defaults, an operator can edit them after the first evaluation.
 */
public interface AccountDeletionBlocker {

    /** Stable identifier, {@code <module>.<snake_name>}, unique across blockers. */
    String key();

    int defaultSortOrder();

    String defaultMessageVi();

    String defaultMessageEn();

    /**
     * Whether the account has unfinished activity of this module. A failed read must throw: returning
     * {@code false} would let an account with running work be deleted.
     */
    boolean blocks(UUID userId);
}
