package com.flowcortex.user.application;

import com.flowcortex.user.api.CurrentUser;
import com.flowcortex.user.api.UserView;
import com.flowcortex.user.domain.AppUser;

/**
 * Maps the internal {@link AppUser} entity to the {@code api} views. The one
 * place that mapping happens, so {@code passwordHash} has exactly one spot it
 * could leak from — and does not.
 */
final class AppUserMapper {

    private AppUserMapper() {
    }

    static CurrentUser toCurrentUser(AppUser user) {
        return new CurrentUser(
                user.getId(),
                user.getOwnerId(),
                user.getEmail(),
                user.getName(),
                user.getRole().name(),
                user.getStatus().name());
    }

    static UserView toUserView(AppUser user) {
        return new UserView(
                user.getId(),
                user.getOwnerId(),
                user.getEmail(),
                user.getName(),
                user.getRole().name(),
                user.getStatus().name(),
                user.getCreatedAt(),
                user.getUpdatedAt());
    }
}
