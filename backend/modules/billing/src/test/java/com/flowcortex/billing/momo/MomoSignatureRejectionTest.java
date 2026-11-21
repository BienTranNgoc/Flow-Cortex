package com.flowcortex.billing.momo;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class MomoSignatureRejectionTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String BUILT = "accessKey=ABCDEFGHIJKLMNOP&amount=1000&orderId=O&requestId=R";

    private static JsonNode answer(String json) {
        return MAPPER.readTree(json);
    }

    @Test
    void codeThirteenWithADifferingAmountNamesAmount() {
        String echoed = "accessKey=ABCDEFGHIJKLMNOP&amount=2000&orderId=O&requestId=R";

        var description = MomoSignatureRejection.describe(BUILT,
                answer("{\"resultCode\":13,\"message\":\"x\",\"subErrors\":[{\"field\":\"signature\",\"message\":\""
                        + echoed + "\"}]}"));

        assertThat(description).isPresent();
        assertThat(description.get().resultCode()).isEqualTo("13");
        assertThat(description.get().differingFields()).containsExactly("amount");
        assertThat(description.get().firstDifferenceIndex()).isEqualTo(BUILT.indexOf("amount=1000") + 7);
        assertThat(description.get().expected()).isEqualTo(echoed);
        assertThat(description.get().built()).isEqualTo(BUILT);
        assertThat(description.get().comparisonComplete()).isTrue();
    }

    @Test
    void codeElevenZeroZeroSevenReadsTheStringOutOfTheProse() {
        var description = MomoSignatureRejection.describe(BUILT, answer("{\"resultCode\":11007,\"message\":"
                + "\"Chu ky khong hop le : accessKey=***&amount=1000&orderId=X&requestId=R.\"}"));

        assertThat(description).isPresent();
        assertThat(description.get().differingFields()).containsExactly("orderId");
        assertThat(description.get().maskedFields()).containsExactly("accessKey");
        assertThat(description.get().comparisonComplete()).isFalse();
    }

    @Test
    void codeTwentyIsDiagnosedAndOtherCodesAreNot() {
        assertThat(MomoSignatureRejection.describe(BUILT, answer("{\"resultCode\":20}"))).isPresent();
        assertThat(MomoSignatureRejection.describe(BUILT, answer("{\"resultCode\":1006}"))).isEmpty();
        assertThat(MomoSignatureRejection.describe(BUILT, answer("{\"resultCode\":0}"))).isEmpty();
        assertThat(MomoSignatureRejection.describe(BUILT, answer("[]"))).isEmpty();
        assertThat(MomoSignatureRejection.describe(BUILT, answer("{\"resultCode\":\"x\"}"))).isEmpty();
    }

    @Test
    void withNoEchoedStringTheRecordSaysNothingWasCompared() {
        var description = MomoSignatureRejection.describe(BUILT, answer("{\"resultCode\":\"20\"}"));

        assertThat(description).isPresent();
        assertThat(description.get().expected()).isNull();
        assertThat(description.get().differingFields()).isEmpty();
        assertThat(description.get().firstDifferenceIndex()).isNull();
        assertThat(description.get().comparisonComplete()).isFalse();
    }

    @Test
    void maskAccessKeyShowsFirstAndLastFourCharacters() {
        assertThat(MomoSignatureRejection.maskAccessKey("accessKey=ABCDEFGHIJKLMNOP&orderId=x"))
                .isEqualTo("accessKey=ABCD…MNOP&orderId=x");
    }

    @Test
    void aShortAccessKeyIsMaskedFullyAndMomosMaskIsKept() {
        assertThat(MomoSignatureRejection.maskAccessKey("accessKey=ABCDEFGHIJK&orderId=x"))
                .isEqualTo("accessKey=***&orderId=x");
        assertThat(MomoSignatureRejection.maskAccessKey("accessKey=***&orderId=x"))
                .isEqualTo("accessKey=***&orderId=x");
    }

    @Test
    void theRenderedWarnLineMasksTheKeyAndNamesTheDifferingFields() {
        var description = MomoSignatureRejection.describe(BUILT, answer("{\"resultCode\":13,\"message\":"
                + "\"m : accessKey=ABCDEFGHIJKLMNOP&amount=2000&orderId=O&requestId=R\"}")).orElseThrow();

        String line = description.toLogLine("query");

        assertThat(line).contains("query", "13", "amount", "ABCD…MNOP");
        assertThat(line).doesNotContain("ABCDEFGHIJKLMNOP").doesNotContain("EFGHIJKL");
    }
}
