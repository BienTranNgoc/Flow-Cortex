package com.flowcortex.app.user;

import com.flowcortex.user.totp.TotpRequirement;

/**
 * The console requirement a TOTP IT flips at runtime, so a switch-off case needs no context of its own. Every
 * IT resets it to {@code true} before each case.
 */
class TestTotpRequirement implements TotpRequirement {

    private volatile boolean adminRequired = true;

    @Override
    public boolean adminRequired() {
        return adminRequired;
    }

    void set(boolean adminRequired) {
        this.adminRequired = adminRequired;
    }

    void reset() {
        adminRequired = true;
    }
}
