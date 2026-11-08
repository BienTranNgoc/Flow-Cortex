package com.flowcortex.support.assistant;

import java.util.UUID;

/** Who asks: a member, or a guest known only by the keyed hashes of its browser session and network address. */
sealed interface Asker {

    /** The quota subject that also keys the per-minute cap and owns the conversation. */
    String subjectKey();

    record Member(UUID userId) implements Asker {

        @Override
        public String subjectKey() {
            return "member:" + userId;
        }
    }

    record Guest(String sessionHash, String networkHash) implements Asker {

        @Override
        public String subjectKey() {
            return "guest-session:" + sessionHash;
        }

        String networkKey() {
            return "guest-ip:" + networkHash;
        }
    }
}
