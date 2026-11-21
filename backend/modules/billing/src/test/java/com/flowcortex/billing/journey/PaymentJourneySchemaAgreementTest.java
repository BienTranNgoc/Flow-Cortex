package com.flowcortex.billing.journey;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.billing.journey.PaymentJourneyEventParserTest.Fixture;
import com.flowcortex.billing.journey.PaymentJourneyEventParserTest.JourneyFixtures;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * Over the parser's fixture table, the parser accepts a body exactly when
 * {@code contracts/payment-journey-event.schema.json} does. The checks a schema cannot state (a duplicated
 * key, size, malformed JSON, trailing content) are asserted against the parser alone.
 */
class PaymentJourneySchemaAgreementTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static Schema schema;

    @BeforeAll
    static void loadSchema() throws IOException {
        String dir = Objects.requireNonNull(System.getProperty("flowcortex.contracts.dir"),
                "system property flowcortex.contracts.dir is not set (see modules/billing/build.gradle.kts)");
        try (InputStream in = Files.newInputStream(Path.of(dir, "payment-journey-event.schema.json"))) {
            schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                    .getSchema(MAPPER.readTree(in));
        }
    }

    static Stream<Arguments> expressible() {
        return JourneyFixtures.all().stream().filter(Fixture::schemaExpressible).map(Arguments::of);
    }

    static Stream<Arguments> notExpressible() {
        return JourneyFixtures.all().stream().filter(fixture -> !fixture.schemaExpressible()).map(Arguments::of);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("expressible")
    void theSchemaAndTheParserGiveTheSameVerdict(Fixture fixture) {
        boolean schemaValid = schema.validate(MAPPER.readTree(fixture.body())).isEmpty();
        boolean parserAccepts = PaymentJourneyEventParser.parse(fixture.body().getBytes(StandardCharsets.UTF_8))
                .isPresent();

        assertThat(schemaValid).as("schema verdict").isEqualTo(parserAccepts);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("notExpressible")
    void aCheckTheSchemaCannotStateIsRefusedByTheParserAlone(Fixture fixture) {
        assertThat(fixture.expected()).isNull();
        assertThat(PaymentJourneyEventParser.parse(fixture.body().getBytes(StandardCharsets.UTF_8))).isEmpty();
    }
}
