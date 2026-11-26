package com.flowcortex.user.phone;

/** The state of the SMS carrier's credential; a bean exists only while a carrier sender is selected. */
@FunctionalInterface
interface CarrierCredentialStatus {

    /** {@code unseeded}, {@code active} or {@code reseed_required}. */
    String state();
}
