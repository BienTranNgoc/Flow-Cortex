package com.flowcortex.profile.application;

import com.flowcortex.profile.domain.ProfileImage.Slot;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The pure rules of profile images: the shape a current image must have and how long a slot's history is. */
public final class ProfileImageRules {

    /** Originals kept per slot; recording one more drops the oldest. */
    public static final int HISTORY_LIMIT = 30;

    private static final int AVATAR_MIN_SIDE = 64;
    private static final int AVATAR_MAX_SIDE = 1024;
    /** The crop rectangle is rounded to whole pixels on each side, so a square may be one pixel off. */
    private static final int AVATAR_TOLERANCE = 1;
    private static final int COVER_MIN_WIDTH = 300;
    private static final int COVER_MAX_WIDTH = 3000;
    private static final int COVER_TOLERANCE = 3;

    private ProfileImageRules() {
    }

    /** Whether a {@code width} x {@code height} picture can be the current image of {@code slot}. */
    public static boolean fits(Slot slot, int width, int height) {
        return switch (slot) {
            case AVATAR -> Math.abs(width - height) <= AVATAR_TOLERANCE
                    && inRange(width, AVATAR_MIN_SIDE, AVATAR_MAX_SIDE)
                    && inRange(height, AVATAR_MIN_SIDE, AVATAR_MAX_SIDE);
            case COVER -> Math.abs((long) width - 3L * height) <= COVER_TOLERANCE
                    && inRange(width, COVER_MIN_WIDTH, COVER_MAX_WIDTH);
        };
    }

    /**
     * The entries past the newest {@link #HISTORY_LIMIT} of a list ordered newest first, oldest first: the
     * ones to remove from the history.
     */
    public static <T> List<T> beyondHistory(List<T> newestFirst) {
        if (newestFirst.size() <= HISTORY_LIMIT) {
            return List.of();
        }
        List<T> rest = new ArrayList<>(newestFirst.subList(HISTORY_LIMIT, newestFirst.size()));
        Collections.reverse(rest);
        return rest;
    }

    private static boolean inRange(int value, int min, int max) {
        return value >= min && value <= max;
    }
}
