package com.flowcortex.billing.profile;

/** The fields of a billing profile a member edits, already trimmed; optional ones are null when absent. */
record ProfileFields(String type, String legalName, String taxCode, String personalIdNumber, String address,
        String email) {

    static final ProfileFields EMPTY = new ProfileFields(null, null, null, null, null, null);
}
