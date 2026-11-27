package com.flowcortex.app.billing;

import com.flowcortex.content.document.ContentDocumentService;
import com.flowcortex.content.document.PublishedDocuments;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Publishes and removes the payment terms document the way an admin would, for the tests that need them. */
final class PaymentTermsFixture {

    static final String SLUG = "legal-payment";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ContentDocumentService documents;
    private final PublishedDocuments publishedDocuments;
    private final JdbcTemplate jdbc;

    PaymentTermsFixture(ContentDocumentService documents, PublishedDocuments publishedDocuments, JdbcTemplate jdbc) {
        this.documents = documents;
        this.publishedDocuments = publishedDocuments;
        this.jdbc = jdbc;
    }

    /** Saves draft {@code draftVersion} (1 for the first publication, then 2, ...) and publishes it; returns its id. */
    UUID publish(int draftVersion) {
        UUID admin = UUID.randomUUID();
        ObjectNode data = JSON.createObjectNode();
        data.set("title", text("Điều khoản thanh toán", "Payment terms"));
        data.set("body", text("Nội dung " + draftVersion, "Body " + draftVersion));
        data.put("docVersion", "1." + draftVersion);
        data.put("effectiveDate", "2026-12-01");
        ObjectNode section = JSON.createObjectNode();
        section.put("type", "legalDoc");
        section.put("schemaVersion", 1);
        section.set("data", data);
        ObjectNode seo = JSON.createObjectNode();
        seo.set("title", text("Tiêu đề", "Title"));
        seo.set("description", text("Mô tả", "Description"));
        int version = documents.saveDraft(SLUG, admin, draftVersion - 1, JSON.createArrayNode().add(section), seo)
                .version();
        documents.publish(SLUG, admin, version);
        return jdbc.queryForObject("SELECT id FROM content_document_revision WHERE slug = ? AND kind = 'publication'"
                + " ORDER BY created_at DESC, id DESC LIMIT 1", UUID.class, SLUG);
    }

    void clear() {
        jdbc.update("DELETE FROM content_document_revision WHERE slug = ?", SLUG);
        jdbc.update("DELETE FROM content_document WHERE slug = ?", SLUG);
        publishedDocuments.invalidate(SLUG);
    }

    private static ObjectNode text(String vi, String en) {
        ObjectNode text = JSON.createObjectNode();
        text.put("vi", vi);
        text.put("en", en);
        return text;
    }
}
