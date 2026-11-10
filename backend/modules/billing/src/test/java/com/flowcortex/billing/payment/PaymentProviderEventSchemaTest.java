package com.flowcortex.billing.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Serializes the provider-event record and validates it against the committed
 * {@code contracts/payment-provider-event.schema.json}; every invalid case is also refused by Bean Validation.
 */
class PaymentProviderEventSchemaTest {

    private static final String ORDER_REF = "fc_0123456789abcdef0123456789abcdef";
    private static final String REQUEST_REF = "5f0c7c1e-8f7e-4a53-9a38-2f6d6f3f9c11";
    private static final Instant AT = Instant.parse("2026-11-10T08:00:00Z");

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    private static Schema schema;

    @BeforeAll
    static void loadSchema() throws IOException {
        String dir = Objects.requireNonNull(System.getProperty("flowcortex.contracts.dir"),
                "system property flowcortex.contracts.dir is not set (see modules/billing/build.gradle.kts)");
        try (InputStream in = Files.newInputStream(Path.of(dir, "payment-provider-event.schema.json"))) {
            schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                    .getSchema(MAPPER.readTree(in));
        }
    }

    private static PaymentProviderEvent.Reference reference(String operationRef, String requestRef) {
        return new PaymentProviderEvent.Reference(operationRef, requestRef, "3141592653");
    }

    private static PaymentProviderEvent full() {
        return new PaymentProviderEvent("1", "momo", PaymentProviderEvent.Kind.INBOUND_PAYMENT_CALLBACK,
                reference(ORDER_REF, REQUEST_REF), "0", "Successful.", 150_000L, AT, "qr", AT);
    }

    private static PaymentProviderEvent allNullable() {
        return new PaymentProviderEvent("1", "momo", PaymentProviderEvent.Kind.TRANSACTION_QUERY,
                new PaymentProviderEvent.Reference(ORDER_REF, REQUEST_REF, null), null, null, null, null, null, AT);
    }

    private static PaymentProviderEvent withReference(PaymentProviderEvent.Reference reference) {
        PaymentProviderEvent base = full();
        return new PaymentProviderEvent(base.schemaVersion(), base.provider(), base.kind(), reference,
                base.resultCode(), base.resultMessage(), base.amount(), base.providerTimestamp(),
                base.providerPayType(), base.receivedAt());
    }

    private static PaymentProviderEvent withResultCode(String resultCode) {
        PaymentProviderEvent base = full();
        return new PaymentProviderEvent(base.schemaVersion(), base.provider(), base.kind(), base.reference(),
                resultCode, base.resultMessage(), base.amount(), base.providerTimestamp(),
                base.providerPayType(), base.receivedAt());
    }

    private static PaymentProviderEvent withAmount(Long amount) {
        PaymentProviderEvent base = full();
        return new PaymentProviderEvent(base.schemaVersion(), base.provider(), base.kind(), base.reference(),
                base.resultCode(), base.resultMessage(), amount, base.providerTimestamp(),
                base.providerPayType(), base.receivedAt());
    }

    private static PaymentProviderEvent withKind(PaymentProviderEvent.Kind kind) {
        PaymentProviderEvent base = full();
        return new PaymentProviderEvent(base.schemaVersion(), base.provider(), kind, base.reference(),
                base.resultCode(), base.resultMessage(), base.amount(), base.providerTimestamp(),
                base.providerPayType(), base.receivedAt());
    }

    private static PaymentProviderEvent withReceivedAt(Instant receivedAt) {
        PaymentProviderEvent base = full();
        return new PaymentProviderEvent(base.schemaVersion(), base.provider(), base.kind(), base.reference(),
                base.resultCode(), base.resultMessage(), base.amount(), base.providerTimestamp(),
                base.providerPayType(), receivedAt);
    }

    @Test
    void aFullRecordConformsToTheSchemaAndBeanValidation() {
        assertThat(schema.validate(MAPPER.valueToTree(full()))).isEmpty();
        assertThat(VALIDATOR.validate(full())).isEmpty();
    }

    @Test
    void aRecordWithAnUpperCaseRequestRefConforms() {
        PaymentProviderEvent event = withReference(reference(ORDER_REF, REQUEST_REF.toUpperCase()));

        assertThat(schema.validate(MAPPER.valueToTree(event))).isEmpty();
        assertThat(VALIDATOR.validate(event)).isEmpty();
    }

    @Test
    void aRecordWhereTheProviderStatedNothingConformsAndKeepsEveryNull() {
        JsonNode tree = MAPPER.valueToTree(allNullable());

        assertThat(schema.validate(tree)).isEmpty();
        assertThat(VALIDATOR.validate(allNullable())).isEmpty();
        assertThat(tree.get("resultCode").isNull()).isTrue();
        assertThat(tree.get("amount").isNull()).isTrue();
        assertThat(tree.get("providerTimestamp").isNull()).isTrue();
        assertThat(tree.get("reference").get("providerTransactionId").isNull()).isTrue();
    }

    @Test
    void theKindIsSerializedAsItsSnakeCaseWireValue() {
        assertThat(MAPPER.valueToTree(full()).get("kind").asString()).isEqualTo("inbound_payment_callback");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(PaymentProviderEvent.Kind.class)
    void everyKindIsSerializedAndAcceptedBySchemaAndBeanValidation(PaymentProviderEvent.Kind kind) {
        PaymentProviderEvent event = withKind(kind);

        assertThat(schema.validate(MAPPER.valueToTree(event))).isEmpty();
        assertThat(VALIDATOR.validate(event)).isEmpty();
    }

    static Stream<Arguments> invalidRecords() {
        return Stream.of(
                Arguments.of("unknown kind", withKind(null)),
                Arguments.of("amount -1", withAmount(-1L)),
                Arguments.of("operationRef from another system",
                        withReference(reference("xo_0123456789abcdef0123456789abcdef", REQUEST_REF))),
                Arguments.of("requestRef not a UUID", withReference(reference(ORDER_REF, "not-a-uuid"))),
                Arguments.of("requestRef 1-1-1-1-1", withReference(reference(ORDER_REF, "1-1-1-1-1"))),
                Arguments.of("resultCode of 33 characters", withResultCode("x".repeat(33))),
                Arguments.of("missing receivedAt", withReceivedAt(null)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidRecords")
    void anInvalidRecordIsRefusedBySchemaAndByBeanValidation(String name, PaymentProviderEvent event) {
        assertThat(schema.validate(MAPPER.valueToTree(event))).as("schema").isNotEmpty();
        assertThat(VALIDATOR.validate(event)).as("bean validation").isNotEmpty();
    }

    @Test
    void aMutatedDocumentWithAnUnknownKindIsRefusedBySchema() {
        ObjectNode tree = (ObjectNode) MAPPER.valueToTree(full());
        tree.put("kind", "chargeback");

        assertThat(schema.validate(tree)).isNotEmpty();
    }

    @Test
    void anExtraPropertyIsRefusedBySchemaAtEveryLevel() {
        ObjectNode top = (ObjectNode) MAPPER.valueToTree(full());
        top.put("userId", UUID.randomUUID().toString());
        ObjectNode nested = (ObjectNode) MAPPER.valueToTree(full());
        ((ObjectNode) nested.get("reference")).put("extra", "x");

        assertThat(schema.validate(top)).isNotEmpty();
        assertThat(schema.validate(nested)).isNotEmpty();
    }
}
