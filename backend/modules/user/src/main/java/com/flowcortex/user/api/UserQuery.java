package com.flowcortex.user.api;

import com.flowcortex.user.domain.AccountState;
import java.util.Optional;

/**
 * The filters and order of a users list: {@code q} is trimmed free text (blank means none), {@code role}
 * a role code, {@code state} one account state. The role code is validated against the {@code role}
 * table by {@link UserService#searchUsers}, so a custom role filters like a seeded one.
 */
public record UserQuery(String q, Optional<String> role, Optional<AccountState> state, Sort sort) {

    public enum Sort { NEWEST, OLDEST, EMAIL, NAME }

    public UserQuery {
        q = q == null ? "" : q.strip();
        role = role == null ? Optional.empty() : role;
        state = state == null ? Optional.empty() : state;
        sort = sort == null ? Sort.NEWEST : sort;
    }

    /** No filter, newest first. */
    public static UserQuery all() {
        return new UserQuery("", Optional.empty(), Optional.empty(), Sort.NEWEST);
    }
}
