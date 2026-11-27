package com.flowcortex.user.accountdeletion;

/** A condition's read failed, so eligibility is unknown and must not be answered as "nothing blocks". */
class EligibilityUnavailableException extends RuntimeException {

    private final String key;

    EligibilityUnavailableException(String key, Throwable cause) {
        super("Deletion eligibility unavailable: condition " + key + " could not be read", cause);
        this.key = key;
    }

    String key() {
        return key;
    }
}
