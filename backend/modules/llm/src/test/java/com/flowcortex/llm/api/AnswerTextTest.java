package com.flowcortex.llm.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.Image;
import org.commonmark.node.Link;
import org.commonmark.node.LinkReferenceDefinition;
import org.commonmark.parser.Parser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AnswerTextTest {

    private static final Parser PARSER = Parser.builder().build();

    @ParameterizedTest
    @ValueSource(strings = {
            "[x]( mailto:a@evil)",
            "[x](mailto:a@evil 'hi')",
            "[x](\nmailto:a@evil)",
            "[Nodi](http:evil.com/x(a(b)))",
            "See [Nodi][r]\n\n[r]:\nhttp:evil.com",
            "<https://evil.com>",
            "<a href=\"https://evil.com\">x</a>",
            "<div><a href=\"https://evil.com\">x</a></div>",
            "![x](https://evil.com/p.png)",
            "[![x](https://evil.com/p.png)](https://evil.com)",
            "\\[x\\](https://evil.com)",
            "[x]: https://evil.com\n\n[x]",
    })
    void noLinkImageDefinitionOrHtmlSurvives(String answer) {
        String plain = AnswerText.withoutLinks(answer);

        assertThat(forbiddenNodes(plain)).as(plain).isEmpty();
        assertThat(plain).doesNotContain("href", "mailto");
    }

    @Test
    void aLinkAndAnImageAreReducedToTheirText() {
        assertThat(AnswerText.withoutLinks("Xem [Nạp credit](/help/nap-credit) và ![logo](https://cdn.x/l.png)."))
                .isEqualTo("Xem Nạp credit và logo.");
        assertThat(AnswerText.withoutLinks("See [Nodi][r]\n\n[r]:\nhttp:evil.com")).isEqualTo("See Nodi");
        assertThat(AnswerText.withoutLinks("<a href=\"https://evil.com\">x</a>")).isEqualTo("x");
    }

    @Test
    void aPlainAnswerKeepsItsMarkdownStructure() {
        String answer = "**Bước 1**: vào `Hồ sơ`.\n\n- Nạp credit\n- Mua gói\n\n1. Mở trang\n2. Chọn *gói*\n\n"
                + "> Lưu ý: giá có thể đổi.\n\n```\nnpm run build\n```";

        assertThat(AnswerText.withoutLinks(answer)).isEqualTo(answer);
    }

    @Test
    void textWithNoMarkupIsLeftAlone() {
        assertThat(AnswerText.withoutLinks("  Vào Hồ sơ rồi chọn Nạp credit.  ")).isEqualTo("Vào Hồ sơ rồi chọn Nạp credit.");
        assertThat(AnswerText.withoutLinks("")).isEmpty();
    }

    private static List<String> forbiddenNodes(String markdown) {
        List<String> found = new ArrayList<>();
        PARSER.parse(markdown).accept(new AbstractVisitor() {
            @Override
            public void visit(Link link) {
                found.add("link");
            }

            @Override
            public void visit(Image image) {
                found.add("image");
            }

            @Override
            public void visit(LinkReferenceDefinition definition) {
                found.add("definition");
            }

            @Override
            public void visit(HtmlInline html) {
                found.add("inline html");
            }

            @Override
            public void visit(HtmlBlock html) {
                found.add("html block");
            }
        });
        return found;
    }
}
