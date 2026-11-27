package com.flowcortex.content.legal;

import com.flowcortex.content.api.LocalizedText;
import java.util.List;

/** The legal documents Nodi publishes, in the order readers see them; slug is {@code legal-<doc>}, path {@code /legal/<doc>}. */
public final class LegalCatalogue {

    /** One legal document: its public name ({@code doc}, the path segment) and its title in both languages. */
    public record Entry(String doc, LocalizedText name) {
    }

    public static final String INDEX_SLUG = "legal-index";

    private static final String SLUG_PREFIX = "legal-";
    private static final String PATH_PREFIX = "/legal/";

    public static final List<Entry> DOCUMENTS = List.of(
            entry("service-conditions", "Các điều kiện hoặc hạn chế trong việc cung cấp dịch vụ",
                    "Conditions and restrictions on service provision"),
            entry("privacy", "Chính sách bảo mật dữ liệu", "Data privacy policy"),
            entry("termination-refund", "Chính sách chấm dứt dịch vụ và hoàn tiền",
                    "Service termination and refund policy"),
            entry("plan-change-renewal", "Chính sách đổi gói, gia hạn dịch vụ, hoàn tiền",
                    "Plan change, renewal and refund policy"),
            entry("pricing", "Chính sách giá", "Pricing policy"),
            entry("delivery", "Chính sách giao hàng", "Delivery policy"),
            entry("payment", "Chính sách thanh toán", "Payment policy"),
            entry("service-provision", "Chính sách về phương thức cung cấp dịch vụ", "Service provision policy"),
            entry("rights-obligations", "Điều khoản về quyền và nghĩa vụ của các bên trên nền tảng",
                    "Rights and obligations of the parties on the platform"),
            entry("complaints", "Phương thức tiếp nhận và giải quyết phản ánh, yêu cầu, khiếu nại",
                    "Feedback, request and complaint handling"),
            entry("terms", "Điều khoản sử dụng nền tảng", "Platform terms of use"));

    private LegalCatalogue() {
    }

    private static Entry entry(String doc, String vi, String en) {
        return new Entry(doc, new LocalizedText(vi, en));
    }

    public static String slugOf(String doc) {
        return SLUG_PREFIX + doc;
    }

    /** The catalogue slugs in catalogue order, the index not included. */
    public static List<String> slugs() {
        return DOCUMENTS.stream().map(entry -> slugOf(entry.doc())).toList();
    }

    /** The member page path of a catalogue slug. */
    public static String pathOf(String slug) {
        if (!isLegalDocument(slug)) {
            throw new IllegalArgumentException("'" + slug + "' is not a legal document");
        }
        return PATH_PREFIX + slug.substring(SLUG_PREFIX.length());
    }

    /** Whether the slug is one of the legal documents; the index's own slug is not. */
    public static boolean isLegalDocument(String slug) {
        return slug != null && DOCUMENTS.stream().anyMatch(entry -> slugOf(entry.doc()).equals(slug));
    }
}
