package com.flowcortex.support.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.support.assistant.TopicGuard.Topic;
import java.text.Normalizer;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class TopicGuardTest {

    private static String topicOf(String question) {
        return TopicGuard.topic(question).map(Topic::value).orElse(null);
    }

    static Stream<Arguments> rows() {
        return Stream.of(
                // money, Vietnamese and English
                Arguments.of("Tôi cần hoàn tiền cho giao dịch", "money"),
                Arguments.of("I want a refund for my payment", "money"),
                Arguments.of("Tại sao tôi bị trừ tiền hai lần?", "money"),
                Arguments.of("There is a chargeback on my invoice", "money"),
                // general policy and pricing questions go to the articles
                Arguments.of("Chính sách hoàn tiền của Nodi là gì?", null),
                Arguments.of("thanh toán credit bằng hình thức nào", null),
                Arguments.of("Gói cước có những loại nào?", null),
                Arguments.of("What is the refund policy?", null),
                Arguments.of("How does billing work?", null),
                // an own case stays mandatory even phrased as a question, and so does a bare noun
                Arguments.of("Tôi muốn yêu cầu hoàn tiền", "money"),
                Arguments.of("Chính sách hoàn tiền áp dụng cho đơn hàng của tôi thế nào?", "money"),
                Arguments.of("How do I get a refund for my order?", "money"),
                Arguments.of("hoàn tiền", "money"),
                // pricing and payment methods with a first-person pronoun still reach the articles
                Arguments.of("mình có các gói nào, giá bao nhiêu, thanh toán bằng tiền mặt không", null),
                Arguments.of("tôi thanh toán bằng hình thức nào được?", null),
                Arguments.of("Giá của Nodi như nào", null),
                // a billing incident or any refund or deduction stays mandatory
                Arguments.of("tôi đã thanh toán nhưng chưa nhận được gói", "money"),
                Arguments.of("sao tôi bị trừ tiền 2 lần", "money"),
                Arguments.of("Tôi cần được refund", "money"),
                Arguments.of("tôi muốn yêu cầu hoàn tiền", "money"),
                Arguments.of("tôi thanh toán rồi mà sao chưa có gói?", "money"),
                // security incidents, not the bare password and security nouns
                Arguments.of("Tài khoản của tôi bị hack", "security"),
                Arguments.of("My account got hacked", "security"),
                Arguments.of("I think there was a data breach", "security"),
                Arguments.of("có người đăng nhập trái phép vào tài khoản", "security"),
                Arguments.of("Làm sao đặt lại mật khẩu?", null),
                Arguments.of("How do I reset my password?", null),
                Arguments.of("Cho tôi xem chính sách bảo mật", null),
                // personal data
                Arguments.of("Tôi muốn xóa dữ liệu cá nhân của mình", "personal_data"),
                Arguments.of("Please delete my personal data", "personal_data"),
                Arguments.of("This is a GDPR request", "personal_data"),
                // legal
                Arguments.of("Tôi muốn khiếu nại và khởi kiện", "legal"),
                Arguments.of("I will file a lawsuit", "legal"),
                Arguments.of("This violates the terms of service", "legal"),
                // violation
                Arguments.of("Đây là hành vi lừa đảo", "violation"),
                Arguments.of("I want to report fraud", "violation"),
                Arguments.of("This is abuse of the platform", "violation"),
                // ordinary help questions
                Arguments.of("Làm sao để tạo một workflow mới?", null),
                Arguments.of("How do I create a workflow?", null),
                Arguments.of("Thời tiết hôm nay thế nào?", null),
                // words that hold a term only by accident of spelling, and unaccented text is not folded
                Arguments.of("Draw a pirate ship for my kids", null),
                Arguments.of("Make a pirate themed video", null),
                Arguments.of("How to manufacture a product video", null),
                Arguments.of("I was debited twice", null),
                Arguments.of("How do I make a video of ostriches running?", null),
                Arguments.of("My avocation is pottery, can Nodi make a reel about it?", null),
                Arguments.of("Write a poem about achates", null),
                Arguments.of("A song for a contratenor voice", null),
                Arguments.of("A video about the Nuremberg tribunal", null),
                Arguments.of("A story about the usurpation of the throne", null),
                Arguments.of("Show the facture of this pottery", null),
                Arguments.of("I want a refund", "money"),
                Arguments.of("My account was hacked", "security"),
                Arguments.of("tai khoan bi hack roi", null),
                Arguments.of("toi muon hoan tien", null),
                Arguments.of("Tôi muốn hoàn tiền", "money"),
                Arguments.of("Lam sao de tao workflow moi", null),
                Arguments.of("Can you summarise this course syllabus?", null),
                Arguments.of("Draw a portrait of my cat", null),
                Arguments.of("Write a post about tariff news", null));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("rows")
    void everyPortedQuestionKeepsItsTopic(String question, String expected) {
        assertThat(topicOf(question)).isEqualTo(expected);
    }

    @Test
    void aDoubleChargeIsMoney() {
        assertThat(TopicGuard.topic("Tại sao tôi bị trừ tiền hai lần?")).contains(Topic.MONEY);
    }

    @Test
    void aHackedAccountInEnglishIsSecurity() {
        assertThat(TopicGuard.topic("My account got hacked yesterday")).contains(Topic.SECURITY);
    }

    @Test
    void theFirstTopicInOrderWins() {
        assertThat(TopicGuard.topic("Tôi bị lừa đảo và mất tiền")).contains(Topic.MONEY);
    }

    @Test
    void aPasswordResetMatchesNothing() {
        assertThat(TopicGuard.topic("Làm sao đổi mật khẩu?")).isEqualTo(Optional.empty());
    }

    @Test
    void theRefundPolicyGoesToTheArticles() {
        assertThat(TopicGuard.topic("Chính sách hoàn tiền của Nodi là gì?")).isEmpty();
        assertThat(TopicGuard.generalPolicyQuestion("Chính sách hoàn tiền của Nodi là gì?")).isTrue();
    }

    @Test
    void anOwnRefundPhrasedAsAQuestionIsMoney() {
        assertThat(TopicGuard.topic("Chính sách hoàn tiền áp dụng cho đơn hàng của tôi thế nào?")).contains(Topic.MONEY);
    }

    @Test
    void aPaymentMethodWithAPronounGoesToTheArticles() {
        assertThat(TopicGuard.topic("tôi thanh toán bằng hình thức nào được?")).isEmpty();
    }

    @Test
    void aBareRefundIsMoney() {
        assertThat(TopicGuard.topic("hoàn tiền")).contains(Topic.MONEY);
        assertThat(TopicGuard.generalPolicyQuestion("hoàn tiền")).isFalse();
    }

    @Test
    void aGeneralMoneyQuestionStillTestsTheNextTopics() {
        assertThat(TopicGuard.topic("Chính sách hoàn tiền có bồi thường không, là gì?")).contains(Topic.LEGAL);
    }

    @Test
    void theQuestionIsMatchedInEitherUnicodeForm() {
        String decomposed = Normalizer.normalize("Tại sao tôi bị trừ tiền hai lần?", Normalizer.Form.NFD);

        assertThat(TopicGuard.topic(decomposed)).contains(Topic.MONEY);
    }

    @Test
    void aNullOrBlankQuestionMatchesNothing() {
        assertThat(TopicGuard.topic(null)).isEmpty();
        assertThat(TopicGuard.topic("   ")).isEmpty();
    }

    @Test
    void theStoredValueIsLowerSnakeCase() {
        assertThat(Topic.PERSONAL_DATA.value()).isEqualTo("personal_data");
        assertThat(Topic.values()).extracting(Topic::value)
                .containsExactly("money", "security", "personal_data", "legal", "violation");
    }
}
