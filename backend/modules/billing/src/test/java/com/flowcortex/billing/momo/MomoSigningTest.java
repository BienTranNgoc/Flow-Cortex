package com.flowcortex.billing.momo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class MomoSigningTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static ObjectNode object() {
        return MAPPER.createObjectNode();
    }

    @Test
    void hmacMatchesTheRfc4231Vector() {
        assertThat(MomoSigning.hmacHex("what do ya want for nothing?", "Jefe"))
                .isEqualTo("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843");
    }

    @Test
    void querySigningStringIsTheFourDeclaredFieldsInOrder() {
        ObjectNode values = object()
                .put("requestId", "R").put("partnerCode", "P").put("orderId", "O").put("accessKey", "A")
                .put("unsigned", "ignored");

        assertThat(MomoSigning.signingString("query", MomoSignedFields.QUERY, values))
                .isEqualTo("accessKey=A&orderId=O&partnerCode=P&requestId=R");
    }

    @Test
    void createRendersItsTenFieldsInDeclaredOrder() {
        ObjectNode values = object();
        for (String field : MomoSignedFields.CREATE) {
            values.put(field, "v-" + field);
        }
        values.put("amount", 50000);
        values.put("extraData", "");

        assertThat(MomoSigning.signingString("create", MomoSignedFields.CREATE, values)).isEqualTo(
                "accessKey=v-accessKey&amount=50000&extraData=&ipnUrl=v-ipnUrl&orderId=v-orderId"
                        + "&orderInfo=v-orderInfo&partnerCode=v-partnerCode&redirectUrl=v-redirectUrl"
                        + "&requestId=v-requestId&requestType=v-requestType");
        assertThat(MomoSignedFields.CREATE).hasSize(10);
    }

    @Test
    void anAbsentDeclaredFieldIsRefusedNamingDeclarationAndField() {
        ObjectNode values = object().put("accessKey", "A").put("orderId", "O").put("partnerCode", "P");

        assertThatThrownBy(() -> MomoSigning.signingString("query", MomoSignedFields.QUERY, values))
                .isInstanceOfSatisfying(MomoSigningException.class, e -> {
                    assertThat(e.kind()).isEqualTo(MomoSigningException.Kind.MISSING_FIELD);
                    assertThat(e.declaration()).isEqualTo("query");
                    assertThat(e.field()).isEqualTo("requestId");
                    assertThat(e.getMessage()).contains("query", "requestId");
                });
    }

    @Test
    void nullBooleanAndFractionalValuesAreRefusedWithoutEchoingTheValue() {
        ObjectNode base = object().put("accessKey", "A").put("orderId", "O").put("partnerCode", "P");

        assertThatThrownBy(() -> MomoSigning.signingString("query", MomoSignedFields.QUERY,
                base.deepCopy().putNull("requestId")))
                .isInstanceOfSatisfying(MomoSigningException.class,
                        e -> assertThat(e.kind()).isEqualTo(MomoSigningException.Kind.MISSING_FIELD));
        assertThatThrownBy(() -> MomoSigning.signingString("query", MomoSignedFields.QUERY,
                base.deepCopy().put("requestId", true)))
                .isInstanceOfSatisfying(MomoSigningException.class,
                        e -> assertThat(e.kind()).isEqualTo(MomoSigningException.Kind.UNSUPPORTED_VALUE));
        assertThatThrownBy(() -> MomoSigning.signingString("query", MomoSignedFields.QUERY,
                base.deepCopy().put("requestId", 12.5)))
                .isInstanceOfSatisfying(MomoSigningException.class, e -> {
                    assertThat(e.kind()).isEqualTo(MomoSigningException.Kind.UNSUPPORTED_VALUE);
                    assertThat(e.getMessage()).doesNotContain("12.5");
                });
    }

    @Test
    void integralNumbersRenderAsPlainDigits() {
        ObjectNode values = object().put("a", 50000).put("b", new BigInteger("1234567890123456789"));

        assertThat(MomoSigning.signingString("x", List.of("a", "b"), values))
                .isEqualTo("a=50000&b=1234567890123456789");
    }

    @Test
    void matchesComparesTheHexAndRefusesNullOrOtherLengths() {
        assertThat(MomoSigning.matches("abcd", "abcd")).isTrue();
        assertThat(MomoSigning.matches("abcd", "abce")).isFalse();
        assertThat(MomoSigning.matches("abcd", "abc")).isFalse();
        assertThat(MomoSigning.matches("abcd", null)).isFalse();
        assertThat(MomoSigning.matches(null, "abcd")).isFalse();
    }
}
