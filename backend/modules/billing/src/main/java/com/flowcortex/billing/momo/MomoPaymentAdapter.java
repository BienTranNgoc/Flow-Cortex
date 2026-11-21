package com.flowcortex.billing.momo;

import com.flowcortex.billing.payment.PaymentOperationRefs;
import com.flowcortex.billing.payment.PaymentProviderEvent;
import com.flowcortex.billing.provider.CallbackVerification;
import com.flowcortex.billing.provider.InitiatePayment;
import com.flowcortex.billing.provider.PaymentAmountLimits;
import com.flowcortex.billing.provider.PaymentInstrument;
import com.flowcortex.billing.provider.PaymentOperationRefusedException;
import com.flowcortex.billing.provider.PaymentOperationRefusedException.Reason;
import com.flowcortex.billing.provider.PaymentProvider;
import com.flowcortex.billing.provider.ProviderCallOutcome;
import com.flowcortex.billing.provider.ProviderCircuitBreaker;
import com.flowcortex.billing.provider.ProviderCircuitOpenException;
import com.flowcortex.billing.provider.ProviderTransportException;
import com.flowcortex.billing.provider.QueryRefund;
import com.flowcortex.billing.provider.QueryTransaction;
import com.flowcortex.billing.provider.Refund;
import com.flowcortex.billing.provider.RefundQueryOutcome;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.math.BigInteger;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * MoMo behind the provider port, for one-time payments. Answers are normalised without interpretation: the
 * result code and message are MoMo's own, and the raw answer travels beside the event. Credentials are read
 * only here, to sign; {@code accessKey} is signed but not sent.
 */
final class MomoPaymentAdapter implements PaymentProvider {

    private static final Logger LOG = LoggerFactory.getLogger(MomoPaymentAdapter.class);
    private static final String KEY = "momo";
    private static final String LANGUAGE = "vi";
    private static final int MAX_MESSAGE_CHARS = 512;
    private static final long MAX_RESPONSE_TIME_MILLIS = 253_402_300_799_999L;
    private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);
    private static final Pattern DIGITS = Pattern.compile("^[0-9]{1,19}$");
    private static final Pattern ZERO = Pattern.compile("^0+$");
    private static final Pattern UUID_SHAPE = Pattern.compile(PaymentOperationRefs.UUID_PATTERN);
    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** An endpoint: its short name for logs and metrics, its path, and what MoMo signs on it. */
    private record Endpoint(String name, String path, String declaration, List<String> fields) {
    }

    private static final Endpoint CREATE =
            new Endpoint("create", MomoSignedFields.CREATE_PATH, "create", MomoSignedFields.CREATE);
    private static final Endpoint QUERY =
            new Endpoint("query", MomoSignedFields.QUERY_PATH, "query", MomoSignedFields.QUERY);
    private static final Endpoint REFUND =
            new Endpoint("refund", MomoSignedFields.REFUND_PATH, "refund", MomoSignedFields.REFUND);
    private static final Endpoint REFUND_QUERY = new Endpoint("refund.query", MomoSignedFields.REFUND_QUERY_PATH,
            "refund.query", MomoSignedFields.REFUND_QUERY);

    private final MomoProperties properties;
    private final MomoRequestTypes requestTypes;
    private final MomoTransport transport;
    private final ProviderCircuitBreaker circuit;
    private final MomoTelemetry telemetry;
    private final Clock clock;

    MomoPaymentAdapter(MomoProperties properties, MomoTransport transport, ProviderCircuitBreaker circuit,
            MomoTelemetry telemetry, Clock clock) {
        this.properties = properties;
        this.requestTypes = new MomoRequestTypes(properties.offeredInstruments().stream()
                .map(wire -> PaymentInstrument.fromWire(wire.trim()).orElseThrow(
                        () -> new IllegalStateException("flowcortex.billing.momo.offered-instruments names an unknown instrument")))
                .toList());
        this.transport = transport;
        this.circuit = circuit;
        this.telemetry = telemetry;
        this.clock = clock;
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public Set<PaymentInstrument> offeredInstruments() {
        return new LinkedHashSet<>(requestTypes.offeredInstruments());
    }

    @Override
    public ProviderCallOutcome initiatePayment(InitiatePayment operation) {
        references(operation.orderRef(), operation.requestRef());
        if (PaymentAmountLimits.outsideProviderRange(operation.amountVnd())) {
            throw new PaymentOperationRefusedException(Reason.AMOUNT_OUTSIDE_PROVIDER_RANGE);
        }
        description(operation.description());
        String requestType = requestTypes.requestType(operation.instrument());

        ObjectNode body = base(operation.orderRef(), operation.requestRef())
                .put("amount", operation.amountVnd())
                .put("orderInfo", operation.description())
                .put("redirectUrl", credential("redirect-url", properties.redirectUrl()))
                .put("ipnUrl", credential("ipn-url", properties.ipnUrl()))
                .put("extraData", "")
                .put("requestType", requestType);
        return call(CREATE, body, PaymentProviderEvent.Kind.PAYMENT_INITIATION, operation.orderRef(),
                operation.requestRef());
    }

    @Override
    public ProviderCallOutcome queryTransaction(QueryTransaction operation) {
        references(operation.orderRef(), operation.requestRef());
        return call(QUERY, base(operation.orderRef(), operation.requestRef()),
                PaymentProviderEvent.Kind.TRANSACTION_QUERY, operation.orderRef(), operation.requestRef());
    }

    @Override
    public ProviderCallOutcome refund(Refund operation) {
        references(operation.orderRef(), operation.requestRef());
        if (operation.amountVnd() <= 0) {
            throw new PaymentOperationRefusedException(Reason.AMOUNT_OUTSIDE_PROVIDER_RANGE);
        }
        description(operation.description());
        String transId = operation.providerTransactionId();
        if (transId == null || !DIGITS.matcher(transId).matches() || new BigInteger(transId).compareTo(LONG_MAX) > 0) {
            throw new PaymentOperationRefusedException(Reason.INVALID_REFERENCE, REFUND.name(), "transId");
        }
        ObjectNode body = base(operation.orderRef(), operation.requestRef())
                .put("amount", operation.amountVnd())
                .put("transId", Long.parseLong(transId))
                .put("description", operation.description());
        return call(REFUND, body, PaymentProviderEvent.Kind.REFUND, operation.orderRef(), operation.requestRef());
    }

    @Override
    public RefundQueryOutcome queryRefund(QueryRefund operation) {
        references(operation.orderRef(), operation.requestRef());
        ProviderCallOutcome outcome;
        try {
            outcome = call(REFUND_QUERY, base(operation.orderRef(), operation.requestRef()),
                    PaymentProviderEvent.Kind.REFUND_QUERY, operation.orderRef(), operation.requestRef());
        } catch (ProviderCircuitOpenException e) {
            throw e;
        } catch (ProviderTransportException e) {
            return new RefundQueryOutcome.Unknown(null);
        }
        return MomoRefundQuery.interpret(outcome.payload(), operation.orderRef());
    }

    @Override
    public CallbackVerification verifyCallback(byte[] rawBody) {
        return MomoCallbackVerifier.verify(rawBody, credential("access-key", properties.accessKey()),
                credential("secret-key", properties.secretKey()), clock);
    }

    private ObjectNode base(String orderRef, String requestRef) {
        return MAPPER.createObjectNode()
                .put("partnerCode", credential("partner-code", properties.partnerCode()))
                .put("requestId", requestRef)
                .put("orderId", orderRef)
                .put("lang", LANGUAGE);
    }

    private static void references(String orderRef, String requestRef) {
        if (!PaymentOperationRefs.isOrderRef(orderRef) || requestRef == null
                || !UUID_SHAPE.matcher(requestRef).matches()) {
            throw new PaymentOperationRefusedException(Reason.INVALID_REFERENCE);
        }
    }

    private static void description(String description) {
        if (description == null || description.isBlank()) {
            throw new PaymentOperationRefusedException(Reason.INVALID_DESCRIPTION);
        }
    }

    /** The error names the property, never a value. */
    private static String credential(String property, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("flowcortex.billing.momo." + property + " is not configured");
        }
        return value;
    }

    private ProviderCallOutcome call(Endpoint endpoint, ObjectNode body, PaymentProviderEvent.Kind kind,
            String orderRef, String requestRef) {
        ObjectNode signingView = body.deepCopy().put("accessKey", credential("access-key", properties.accessKey()));
        String signingString;
        try {
            signingString = MomoSigning.signingString(endpoint.declaration(), endpoint.fields(), signingView);
        } catch (MomoSigningException e) {
            throw new PaymentOperationRefusedException(Reason.SIGNING_FAILED, e);
        }
        ObjectNode sent = body.deepCopy().put("signature",
                MomoSigning.hmacHex(signingString, credential("secret-key", properties.secretKey())));

        long startedAt = System.nanoTime();
        Answer answered;
        try {
            answered = circuit.run(KEY, endpoint.name(), () -> {
                JsonNode received = transport.post(endpoint.path(), sent);
                return new Answer(received, event(endpoint, kind, orderRef, requestRef, received));
            });
        } catch (ProviderCircuitOpenException e) {
            throw e;
        } catch (ProviderTransportException e) {
            telemetry.failed(endpoint.name(), e.kind(), Duration.ofNanos(System.nanoTime() - startedAt));
            throw e;
        }
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);
        telemetry.answered(endpoint.name(), answered.event().resultCode(), elapsed);
        diagnoseSignature(endpoint, signingString, answered.payload());
        return new ProviderCallOutcome(answered.event(), answered.payload());
    }

    /** An answer with the event built from it, so an unusable answer fails inside the circuit. */
    private record Answer(JsonNode payload, PaymentProviderEvent event) {
    }

    private void diagnoseSignature(Endpoint endpoint, String signingString, JsonNode answer) {
        MomoSignatureRejection.describe(signingString, answer).ifPresent(rejection -> {
            telemetry.signatureFailure(MomoTelemetry.OUTBOUND_REJECTED);
            LOG.warn("{}", rejection.toLogLine(endpoint.name()));
        });
    }

    private PaymentProviderEvent event(Endpoint endpoint, PaymentProviderEvent.Kind kind, String orderRef,
            String requestRef, JsonNode answer) {
        String message = blankToNull(text(answer.get("message")));
        PaymentProviderEvent event = new PaymentProviderEvent("1", KEY, kind,
                new PaymentProviderEvent.Reference(orderRef, requestRef, transactionId(answer.get("transId"))),
                text(answer.has("resultCode") ? answer.get("resultCode") : answer.get("errorCode")),
                message == null || message.length() <= MAX_MESSAGE_CHARS ? message : message.substring(0, MAX_MESSAGE_CHARS),
                amount(answer.get("amount")), responseTime(answer.get("responseTime")),
                blankToNull(text(answer.get("payType"))), clock.instant());
        if (!VALIDATOR.validate(event).isEmpty()) {
            throw new ProviderTransportException(ProviderTransportException.Kind.MALFORMED_RESPONSE, KEY,
                    endpoint.path(), null);
        }
        return event;
    }

    /** MoMo answers transaction id 0 for an order it holds no transaction for: that is an absence. */
    private static String transactionId(JsonNode node) {
        String text = blankToNull(text(node));
        return text == null || ZERO.matcher(text.trim()).matches() ? null : text;
    }

    private static Long amount(JsonNode node) {
        if (node == null || !node.isIntegralNumber()) {
            return null;
        }
        BigInteger value = node.bigIntegerValue();
        return value.signum() >= 0 && value.compareTo(LONG_MAX) <= 0 ? value.longValueExact() : null;
    }

    private static Instant responseTime(JsonNode node) {
        if (node == null || !node.isIntegralNumber()) {
            return null;
        }
        BigInteger value = node.bigIntegerValue();
        return value.signum() >= 0 && value.compareTo(BigInteger.valueOf(MAX_RESPONSE_TIME_MILLIS)) <= 0
                ? Instant.ofEpochMilli(value.longValueExact()) : null;
    }

    private static String text(JsonNode node) {
        if (node == null) {
            return null;
        }
        if (node.isString()) {
            return node.asString();
        }
        return node.isIntegralNumber() ? node.bigIntegerValue().toString() : null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
