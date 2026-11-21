package com.flowcortex.connector.application;

import com.flowcortex.connector.api.ConnectorException;
import com.flowcortex.connector.api.ConnectorException.Reason;

/**
 * Who may reach a connector, as one ordered table: the first row that fails is the only answer, so two
 * failing checks never answer differently by route. An unreadable credential is the last check but is
 * only known once the tokens are read after an {@link Verdict#allowed() allow}.
 */
public final class ConnectorUseGate {

    /** USE sends or calls the third party; MANAGE only reads or edits the member's own connection. */
    public enum Operation { USE, MANAGE }

    /**
     * @param connected the member has an active connection for the provider
     * @param usageEnabled the member's usage switch for that connection
     */
    public record GateInput(boolean memberSuspended, boolean switchOn, boolean connected, boolean usageEnabled,
            Operation operation) {
    }

    /** {@code reason} is {@code null} when the call is allowed. */
    public record Verdict(Reason reason) {

        static final Verdict ALLOW = new Verdict(null);

        public boolean allowed() {
            return reason == null;
        }

        /** The typed failure of a refusal. */
        public ConnectorException exception() {
            if (reason == null) {
                throw new IllegalStateException("an allowed call has no failure");
            }
            return ConnectorException.of(reason);
        }
    }

    private ConnectorUseGate() {
    }

    public static Verdict decide(GateInput input) {
        if (input.memberSuspended()) {
            return new Verdict(Reason.SUSPENDED);
        }
        if (!input.switchOn()) {
            return new Verdict(Reason.TURNED_OFF);
        }
        if (!input.connected()) {
            return new Verdict(Reason.NOT_CONNECTED);
        }
        if (input.operation() == Operation.USE && !input.usageEnabled()) {
            return new Verdict(Reason.USAGE_DISABLED);
        }
        return Verdict.ALLOW;
    }
}
