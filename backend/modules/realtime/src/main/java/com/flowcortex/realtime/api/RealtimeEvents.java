package com.flowcortex.realtime.api;

/**
 * Producer port: hands an event to every backend instance's open streams. Never blocks the
 * caller and never throws; a delivery that fails is logged and repaired by the client's resync.
 */
public interface RealtimeEvents {

    /**
     * @param topic   the topic the event belongs to, for example {@code run:<uuid>}
     * @param type    the SSE event name
     * @param payload serialized to the JSON {@code data} of the event
     */
    void publish(String topic, String type, Object payload);
}
