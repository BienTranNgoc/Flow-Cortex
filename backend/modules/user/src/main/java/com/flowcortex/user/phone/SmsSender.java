package com.flowcortex.user.phone;

/** Sends one SMS. Called inside the send transaction, so a failure leaves no challenge behind. */
interface SmsSender {

    void send(String phoneE164, String body);
}
