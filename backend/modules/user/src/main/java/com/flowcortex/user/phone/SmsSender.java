package com.flowcortex.user.phone;

/** Sends one SMS, called outside any transaction. */
interface SmsSender {

    /**
     * @throws SmsSendFailure when the message was not taken: {@code UNAVAILABLE} (nothing was sent, a
     *     later attempt may succeed) or {@code NUMBER_REJECTED}. Any other exception is read as unavailable.
     */
    SmsDelivery send(SmsMessage message);
}
