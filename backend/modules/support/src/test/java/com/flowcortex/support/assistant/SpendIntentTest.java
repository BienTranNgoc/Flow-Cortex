package com.flowcortex.support.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class SpendIntentTest {

    private static final String GOLDEN = "/support/guest-spend-intent.golden.json";

    private static List<String> golden(String field) throws IOException {
        try (InputStream in = SpendIntentTest.class.getResourceAsStream(GOLDEN)) {
            JsonNode node = JsonMapper.builder().build().readTree(in).get(field);
            List<String> messages = new ArrayList<>();
            node.forEach(message -> messages.add(message.asString()));
            return messages;
        }
    }

    @Test
    void everyGoldenSpendMessageIsASpendRequest() throws IOException {
        List<String> messages = golden("spend");

        assertThat(messages).hasSize(25);
        assertThat(messages).allSatisfy(message -> assertThat(SpendIntent.isSpendRequest(message))
                .as(message).isTrue());
    }

    @Test
    void everyGoldenNotSpendMessageIsNot() throws IOException {
        List<String> messages = golden("notSpend");

        assertThat(messages).hasSize(26);
        assertThat(messages).allSatisfy(message -> assertThat(SpendIntent.isSpendRequest(message))
                .as(message).isFalse());
    }

    @Test
    void accentedAndUnaccentedSpellingsAgree() {
        assertThat(SpendIntent.isSpendRequest("tạo ảnh sản phẩm"))
                .isEqualTo(SpendIntent.isSpendRequest("tao anh san pham"))
                .isTrue();
    }

    @Test
    void thePronounAnhInAnAccentedMessageIsNotAnImage() {
        assertThat(SpendIntent.isSpendRequest("tạo anh ấy một tài khoản")).isFalse();
    }

    @Test
    void dungIsTheVerbOnlyAsDung() {
        assertThat(SpendIntent.isSpendRequest("sử dụng workflow mới")).isFalse();
        assertThat(SpendIntent.isSpendRequest("dựng video ra mắt")).isTrue();
    }

    @ParameterizedTest(name = "\"{0}\" is not a spend request")
    @ValueSource(strings = {
            "Mình thích lắm video này",
            "Quả táo trong video này",
            "Cháy hàng workflow mới",
            "Tạo giúp mình nhắc nhở hàng ngày",
            "Tạo môi trường sống"})
    void aFoldedWordThatIsAnotherWordWhenAccentedIsNotAVerbOrNoun(String message) {
        assertThat(SpendIntent.isSpendRequest(message)).isFalse();
    }

    @ParameterizedTest(name = "help cue \"{0}\" in \"{1}\"")
    @CsvSource(delimiter = '|', value = {
            "lỗi|mình tạo video mà cứ báo lỗi",
            "có … không|bên mình có tạo được video dọc không",
            "gì|tạo video cần chuẩn bị gì",
            "hỗ trợ|tạo video có hỗ trợ phụ đề tiếng Việt chưa",
            "yêu cầu|tạo video có yêu cầu đăng nhập trước à"})
    void aHelpCueMakesItAHelpQuestion(String cue, String message) {
        assertThat(SpendIntent.isSpendRequest(message)).isFalse();
    }

    @Test
    void chayIsASpendOnlyAsTheOpeningWord() {
        assertThat(SpendIntent.isSpendRequest("tôi đã chạy workflow hôm qua")).isFalse();
        assertThat(SpendIntent.isSpendRequest("chạy workflow tạo thumbnail")).isTrue();
    }

    @Test
    void theNounMustSitWithinFiveWordsOfTheVerb() {
        assertThat(SpendIntent.isSpendRequest("tạo một hai ba bốn video")).isTrue();
        assertThat(SpendIntent.isSpendRequest("tạo một hai ba bốn năm video")).isFalse();
    }

    @Test
    void aNullOrBlankMessageIsNotASpendRequest() {
        assertThat(SpendIntent.isSpendRequest(null)).isFalse();
        assertThat(SpendIntent.isSpendRequest("")).isFalse();
    }

    @Test
    void aMessageInDecomposedUnicodeAgreesWithTheComposedOne() {
        String decomposed = Normalizer.normalize("tạo ảnh sản phẩm", Normalizer.Form.NFD);

        assertThat(SpendIntent.isSpendRequest(decomposed)).isTrue();
    }
}
