package com.flowcortex.llm.api;

import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.Image;
import org.commonmark.node.Link;
import org.commonmark.node.LinkReferenceDefinition;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.markdown.MarkdownRenderer;

/** Cleans a model answer before it is stored or shown. */
public final class AnswerText {

    private static final Parser MARKDOWN = Parser.builder().build();
    private static final MarkdownRenderer MARKDOWN_OUT = MarkdownRenderer.builder().build();

    private AnswerText() {
    }

    /**
     * The answer with every Markdown link and image reduced to its text, reference definitions and raw
     * HTML removed: the provider could otherwise point outside the retrieved articles, which reach the
     * reader as sources. Works on the parsed document, because no pattern matches the CommonMark link
     * grammar (nested parentheses, titles, destinations on the next line, reference-style links).
     */
    public static String withoutLinks(String text) {
        Node document = MARKDOWN.parse(text);
        LinkFlattener flattener = new LinkFlattener();
        document.accept(flattener);
        return flattener.changed ? MARKDOWN_OUT.render(document).strip() : text.strip();
    }

    /**
     * Replaces each link and image by its children (the visible text or alt text) and drops the rest.
     * An answer it leaves alone is kept byte for byte, so the Markdown writer only ever escapes text
     * of an answer that had markup to remove.
     */
    private static final class LinkFlattener extends AbstractVisitor {

        private boolean changed;

        @Override
        public void visit(Link link) {
            flatten(link);
        }

        @Override
        public void visit(Image image) {
            flatten(image);
        }

        @Override
        public void visit(LinkReferenceDefinition definition) {
            drop(definition);
        }

        @Override
        public void visit(HtmlInline html) {
            drop(html);
        }

        @Override
        public void visit(HtmlBlock html) {
            drop(html);
        }

        private void drop(Node node) {
            changed = true;
            node.unlink();
        }

        private void flatten(Node node) {
            changed = true;
            visitChildren(node);
            for (Node child = node.getFirstChild(); child != null; child = node.getFirstChild()) {
                node.insertBefore(child);
            }
            node.unlink();
        }
    }
}
