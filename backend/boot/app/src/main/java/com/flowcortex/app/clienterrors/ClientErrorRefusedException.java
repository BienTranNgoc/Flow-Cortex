package com.flowcortex.app.clienterrors;

/** A refused report; the message is the code only, never anything the caller sent. */
class ClientErrorRefusedException extends RuntimeException {

    private final transient ClientErrorRefusal refusal;

    ClientErrorRefusedException(ClientErrorRefusal refusal) {
        super(refusal.code(), null, false, false);
        this.refusal = refusal;
    }

    ClientErrorRefusal refusal() {
        return refusal;
    }
}
