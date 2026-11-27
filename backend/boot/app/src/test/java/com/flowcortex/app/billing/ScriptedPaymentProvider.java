package com.flowcortex.app.billing;

import com.flowcortex.billing.payment.PaymentProviderEvent;
import com.flowcortex.billing.payment.PaymentProviderEvent.Kind;
import com.flowcortex.billing.payment.PaymentProviderEvent.Reference;
import com.flowcortex.billing.provider.CallbackVerification;
import com.flowcortex.billing.provider.InitiatePayment;
import com.flowcortex.billing.provider.PaymentInstrument;
import com.flowcortex.billing.provider.PaymentProvider;
import com.flowcortex.billing.provider.ProviderCallOutcome;
import com.flowcortex.billing.provider.QueryRefund;
import com.flowcortex.billing.provider.QueryTransaction;
import com.flowcortex.billing.provider.Refund;
import com.flowcortex.billing.provider.RefundQueryOutcome;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * A {@code momo} provider that answers transaction queries as a test scripts them and remembers what it
 * was asked. Everything else a provider does is out of reach of the reconciliation and refused here.
 */
class ScriptedPaymentProvider implements PaymentProvider {

    private final List<QueryTransaction> queries = new CopyOnWriteArrayList<>();
    private volatile Function<QueryTransaction, ProviderCallOutcome> script = query -> {
        throw new IllegalStateException("no answer scripted");
    };

    List<QueryTransaction> queries() {
        return queries;
    }

    void reset() {
        queries.clear();
        script = query -> {
            throw new IllegalStateException("no answer scripted");
        };
    }

    /** Every query is answered with this code, amount and provider transaction id, stamped now. */
    void answerAll(String code, Long amount, String providerTransactionId) {
        script = query -> new ProviderCallOutcome(
                answer(query.orderRef(), code, amount, providerTransactionId, Instant.now()), null);
    }

    void script(Function<QueryTransaction, ProviderCallOutcome> next) {
        script = next;
    }

    static PaymentProviderEvent answer(String orderRef, String code, Long amount, String providerTransactionId,
            Instant at) {
        return new PaymentProviderEvent("1", "momo", Kind.TRANSACTION_QUERY,
                new Reference(orderRef, UUID.randomUUID().toString(), providerTransactionId), code, "Message", amount,
                at, null, at);
    }

    @Override
    public String key() {
        return "momo";
    }

    @Override
    public Set<PaymentInstrument> offeredInstruments() {
        return Set.of();
    }

    @Override
    public ProviderCallOutcome initiatePayment(InitiatePayment operation) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ProviderCallOutcome queryTransaction(QueryTransaction operation) {
        queries.add(operation);
        return script.apply(operation);
    }

    @Override
    public ProviderCallOutcome refund(Refund operation) {
        throw new UnsupportedOperationException();
    }

    @Override
    public RefundQueryOutcome queryRefund(QueryRefund operation) {
        throw new UnsupportedOperationException();
    }

    @Override
    public CallbackVerification verifyCallback(byte[] rawBody) {
        throw new UnsupportedOperationException();
    }
}
