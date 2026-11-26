package com.flowcortex.support.assistant;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Curated Vietnamese and English phrase sets that route a sensitive question to the support team before any
 * article is retrieved. Terms match as case-insensitive substrings and lean toward a handoff; the security set
 * keys on incidents, never on the bare "password" or "security" nouns, so a password reset stays a help question.
 */
final class TopicGuard {

    /** Declaration order is the evaluation order, so the reported topic is deterministic on overlap. */
    enum Topic {
        MONEY("money"),
        SECURITY("security"),
        PERSONAL_DATA("personal_data"),
        LEGAL("legal"),
        VIOLATION("violation");

        private final String value;

        Topic(String value) {
            this.value = value;
        }

        /** The lower snake case name the reply and the telemetry carry. */
        String value() {
            return value;
        }
    }

    /** A refund, a deduction or a transfer: an incident on their own. */
    private static final List<String> MONEY_INCIDENT_TERMS = List.of(
            "hoàn tiền", "trừ tiền", "mất tiền", "rút tiền", "chuyển khoản", "giao dịch", "gian lận thanh toán",
            "refund", "charged", "charge", "chargeback", "transaction", "credit card");

    /** Billing nouns that a plain pricing or payment-method question uses too. */
    private static final List<String> MONEY_BILLING_TERMS = List.of(
            "thanh toán", "nạp tiền", "hóa đơn", "hoá đơn", "gói cước", "nâng cấp gói", "phí dịch vụ", "tính phí",
            "payment", "billing", "invoice", "subscription", "purchase", "pricing");

    private static final Map<Topic, List<String>> TERMS = Map.of(
            Topic.MONEY, concat(MONEY_INCIDENT_TERMS, MONEY_BILLING_TERMS),
            Topic.SECURITY, List.of(
                    "bị hack", "hack tài khoản", "xâm nhập", "đánh cắp tài khoản", "lộ mật khẩu", "rò rỉ",
                    "đăng nhập trái phép", "chiếm đoạt tài khoản", "tài khoản bị khóa", "bị tấn công",
                    "truy cập trái phép",
                    "hacked", "get hacked", "got hacked", "security breach", "data breach", "breached",
                    "compromised", "unauthorized access", "unauthorized login", "phishing", "stolen account",
                    "account locked", "account was locked"),
            Topic.PERSONAL_DATA, List.of(
                    "dữ liệu cá nhân", "thông tin cá nhân", "thông tin nhạy cảm", "quyền riêng tư", "xóa dữ liệu",
                    "xoá dữ liệu", "cccd", "cmnd", "căn cước",
                    "personal data", "personal information", "privacy", "sensitive data", "delete my data",
                    "data deletion", "gdpr", "pii"),
            Topic.LEGAL, List.of(
                    "pháp lý", "pháp luật", "khiếu nại", "khởi kiện", "hợp đồng", "điều khoản dịch vụ",
                    "tranh chấp", "luật sư", "bồi thường",
                    "legal", "lawsuit", "lawyer", "sue you", "contract", "terms of service", "dispute",
                    "liability", "compliance"),
            Topic.VIOLATION, List.of(
                    "vi phạm", "lừa đảo", "gian lận", "lạm dụng", "quấy rối", "báo cáo vi phạm", "nội dung cấm",
                    "chiếm đoạt",
                    "violation", "fraud", "abuse", "scam", "harassment", "report abuse", "illegal", "cheating",
                    "exploit"));

    /** Cues that a money message asks about the rules rather than reporting a case; folded. */
    private static final List<String> POLICY_QUESTION_CUES = List.of(
            "chinh sach", "la gi", "nhu the nao", "the nao", "hinh thuc nao", "cach nao", "co nhung", "bao lau",
            "bao nhieu", "goi nao", "hinh thuc", "nhung goi", "cac goi",
            "policy", "what is", "what are", "how do", "how does", "how long", "which ");

    /** Cues that a billing message reports something wrong with the asker's own payment; folded. */
    private static final List<String> BILLING_INCIDENT_CUES = List.of(
            "bi tru", "tru nham", "chua nhan", "khong nhan", "chua co", "chua duoc", "that bai", "khong thanh cong",
            "bi loi", "loi thanh toan", "nham", "da thanh toan", "da chuyen", "roi ma",
            "failed", "not received", "didn't", "twice", "2 lan", "hai lan");

    /** First-person and ownership markers that make a money message about the asker's own case; folded. */
    private static final Set<String> PERSONAL_CASE_WORDS = Set.of(
            "toi", "minh", "em", "my", "me", "i", "mine", "account", "order");

    private static final List<String> PERSONAL_CASE_PHRASES = List.of("tai khoan", "don hang", "giao dich");

    private TopicGuard() {
    }

    /** The first topic whose set matches the question, in {@link Topic} order, or empty. */
    static Optional<Topic> topic(String question) {
        String text = TextFold.lower(question);
        for (Topic topic : Topic.values()) {
            if (!containsAny(text, TERMS.get(topic))) {
                continue;
            }
            if (topic == Topic.MONEY && generalPolicyQuestion(question)) {
                continue;
            }
            return Optional.of(topic);
        }
        return Optional.empty();
    }

    /**
     * A money question about the rules (policy, pricing, how payment works) that an article answers. It needs a
     * policy cue; with a refund or deduction term any ownership marker keeps it personal, with billing nouns only
     * together with an incident cue. A bare "hoàn tiền" has no cue and stays a handoff.
     */
    static boolean generalPolicyQuestion(String question) {
        String folded = TextFold.fold(question);
        if (POLICY_QUESTION_CUES.stream().noneMatch(folded::contains)) {
            return false;
        }
        if (!containsAny(TextFold.lower(question), MONEY_INCIDENT_TERMS)) {
            return !(hasBillingIncidentCue(question, folded) && isPersonalMoneyCase(folded));
        }
        return !isPersonalMoneyCase(folded);
    }

    private static boolean hasBillingIncidentCue(String question, String folded) {
        // The accented "lost" verb is read before folding: folded it is "mat", which the word for cash
        // ("tiền mặt") also produces.
        if (TextFold.lower(question).contains("mất")) {
            return true;
        }
        return BILLING_INCIDENT_CUES.stream().anyMatch(folded::contains);
    }

    private static boolean isPersonalMoneyCase(String folded) {
        if (PERSONAL_CASE_PHRASES.stream().anyMatch(folded::contains)) {
            return true;
        }
        for (String token : folded.split("[^\\p{L}\\p{N}']+")) {
            if (PERSONAL_CASE_WORDS.contains(token.replaceAll("'.*$", ""))) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsAny(String text, List<String> terms) {
        return terms.stream().anyMatch(text::contains);
    }

    private static List<String> concat(List<String> first, List<String> second) {
        List<String> all = new ArrayList<>(first);
        all.addAll(second);
        return List.copyOf(all);
    }
}
