package com.flowcortex.user.totp;

import java.util.Arrays;

/** The first factor that opened a sign-in challenge; completion re-runs that factor's own account checks. */
public enum ChallengeOrigin {
    PASSWORD("password"),
    GOOGLE("google"),
    LINK("link");

    private final String column;

    ChallengeOrigin(String column) {
        this.column = column;
    }

    String column() {
        return column;
    }

    static ChallengeOrigin fromColumn(String column) {
        return Arrays.stream(values()).filter(origin -> origin.column.equals(column)).findFirst()
                .orElseThrow(() -> new IllegalStateException("Unknown login_challenge origin: " + column));
    }
}
