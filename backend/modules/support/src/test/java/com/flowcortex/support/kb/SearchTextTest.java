package com.flowcortex.support.kb;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class SearchTextTest {

    @Test
    void lowerCasesAndStripsVietnameseDiacritics() {
        assertThat(SearchText.normalize("Nạp CREDIT")).isEqualTo("nap credit");
        assertThat(SearchText.normalize("Đổi")).isEqualTo("doi");
        assertThat(SearchText.normalize("đăng nhập")).isEqualTo("dang nhap");
    }

    @Test
    void tokensAreTheDistinctWordsOfAtLeastTwoCharacters() {
        assertThat(SearchText.tokens("Nạp  credit, nạp? a 2 ví")).containsExactly("nap", "credit", "vi");
        assertThat(SearchText.tokens("n")).isEmpty();
    }

    @Test
    void aTitleHitScoresTwiceABodyHit() {
        List<String> tokens = SearchText.tokens("nap credit");

        assertThat(KnowledgeBaseService.score(tokens, "Nạp credit", "nap credit\nchon goi")).isEqualTo(4);
        assertThat(KnowledgeBaseService.score(tokens, "Ví", "vi\nnap tien bang credit")).isEqualTo(2);
    }
}
