package com.flowcortex.cvcheck.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CvDeterministicAuditor {

    public enum Status {
        PASS, WARN, FAIL
    }

    public record AtsCheck(String id, Status status, String detail, String standard) {
    }

    public record AuditResult(List<AtsCheck> checks, int metricCount, int wordCount, int pageCount) {

        public Status statusOf(String id) {
            return checks.stream()
                    .filter(c -> c.id().equals(id))
                    .map(AtsCheck::status)
                    .findFirst()
                    .orElse(Status.PASS);
        }
    }

    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern PHONE = Pattern.compile("(?:\\+?\\d[\\d\\s().-]{7,}\\d)");
    private static final Pattern PROFILE_LINK = Pattern.compile(
            "(?:github\\.com|linkedin\\.com|gitlab\\.com|bitbucket\\.org|portfolio|https?://)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern METRIC = Pattern.compile(
            "(?:\\d+(?:[.,]\\d+)?\\s*%|\\$\\s*\\d+[\\d.,]*[kKmMbB]?|\\d+[\\d.,]*\\s*(?:triệu|tỷ|vnd|usd|ms|sec|users|người dùng|khách hàng|api|apis|dự án|projects|x)\\b|\\b\\d{2,}\\+)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern PASSIVE_PHRASE = Pattern.compile(
            "(?:chịu trách nhiệm|phụ trách|tham gia vào|responsible for|duties included|worked on|helped with|assisted in)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern ACTION_VERB = Pattern.compile(
            "\\b(?:built|designed|engineered|developed|implemented|optimized|reduced|increased|launched|led|architected|automated|deployed|improved|delivered|xây dựng|thiết kế|tối ưu|triển khai|phát triển|dẫn dắt|cải thiện|tự động hóa|giảm|tăng)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private CvDeterministicAuditor() {
    }

    public static AuditResult audit(String cvText, int pageCount) {
        return audit(cvText, pageCount, "vi");
    }

    public static AuditResult audit(String cvText, int pageCount, String lang) {
        boolean en = isEnglish(lang);
        String text = cvText == null ? "" : cvText.strip();
        String lower = text.toLowerCase(Locale.ROOT);
        int wordCount = text.isEmpty() ? 0 : text.split("\\s+").length;
        int metricCount = countMatches(METRIC, text);

        List<AtsCheck> checks = new ArrayList<>();
        checks.add(checkPageCount(pageCount, en));
        checks.add(checkContactInfo(text, en));
        checks.add(checkStandardSections(lower, en));
        checks.add(checkQuantifiedMetrics(metricCount, en));
        checks.add(checkWordCount(wordCount, en));
        checks.add(checkActionVerbs(text, en));

        return new AuditResult(List.copyOf(checks), metricCount, wordCount, pageCount);
    }

    public static boolean isEnglish(String lang) {
        return lang != null && lang.strip().toLowerCase(Locale.ROOT).startsWith("en");
    }

    private static AtsCheck checkPageCount(int pageCount, boolean en) {
        if (pageCount <= 0) {
            return new AtsCheck("page_count", Status.PASS,
                    en ? "Plain text input (no PDF page limit)" : "Văn bản trực tiếp (không giới hạn trang PDF)",
                    "Harvard OCS");
        }
        if (pageCount <= 2) {
            return new AtsCheck("page_count", Status.PASS,
                    en ? pageCount + (pageCount == 1 ? " page" : " pages") + " (meets 1–2 page standard)"
                            : pageCount + " trang (đạt chuẩn 1–2 trang)",
                    "Harvard OCS");
        }
        if (pageCount == 3) {
            return new AtsCheck("page_count", Status.WARN,
                    en ? "3 pages (consider trimming to 1–2 pages)" : "3 trang (nên rút gọn còn 1–2 trang)",
                    "Harvard OCS");
        }
        return new AtsCheck("page_count", Status.FAIL,
                en ? pageCount + " pages (exceeds 2-page standard)"
                        : pageCount + " trang (vượt quá giới hạn chuẩn 2 trang)",
                "Harvard OCS");
    }

    private static AtsCheck checkContactInfo(String text, boolean en) {
        boolean hasEmail = EMAIL.matcher(text).find();
        boolean hasPhone = PHONE.matcher(text).find();
        boolean hasLink = PROFILE_LINK.matcher(text).find();
        if (hasEmail && hasPhone && hasLink) {
            return new AtsCheck("contact_info", Status.PASS,
                    en ? "Complete Email, Phone, and GitHub/LinkedIn link"
                            : "Đầy đủ Email, SĐT và liên kết GitHub/LinkedIn",
                    "SHRM ATS");
        }
        if (hasEmail && hasPhone) {
            return new AtsCheck("contact_info", Status.WARN,
                    en ? "Email & Phone found; consider adding GitHub/LinkedIn"
                            : "Có Email & SĐT, nên bổ sung GitHub/LinkedIn",
                    "SHRM ATS");
        }
        return new AtsCheck("contact_info", Status.FAIL,
                en ? "Missing Email or Phone number" : "Thiếu Email hoặc Số điện thoại liên hệ",
                "SHRM ATS");
    }

    private static AtsCheck checkStandardSections(String lower, boolean en) {
        boolean hasExp = containsAny(lower, "experience", "kinh nghiệm", "projects", "dự án", "work history");
        boolean hasEdu = containsAny(lower, "education", "học vấn", "university", "đại học", "college", "bachelor");
        boolean hasSkills = containsAny(lower, "skills", "kỹ năng", "technologies", "công nghệ", "tech stack");
        int found = (hasExp ? 1 : 0) + (hasEdu ? 1 : 0) + (hasSkills ? 1 : 0);
        if (found == 3) {
            return new AtsCheck("standard_sections", Status.PASS,
                    en ? "All 3 core ATS sections present (Experience/Projects, Education, Skills)"
                            : "Đủ 3 mục cốt lõi (Kinh nghiệm/Dự án, Học vấn, Kỹ năng)",
                    "SHRM ATS");
        }
        if (found == 2) {
            return new AtsCheck("standard_sections", Status.WARN,
                    en ? "Found 2/3 core ATS sections" : "Tìm thấy 2/3 mục cốt lõi chuẩn ATS",
                    "SHRM ATS");
        }
        return new AtsCheck("standard_sections", Status.FAIL,
                en ? "Missing standard ATS section headings (" + found + "/3)"
                        : "Thiếu các tiêu đề mục chuẩn ATS (" + found + "/3)",
                "SHRM ATS");
    }

    private static AtsCheck checkQuantifiedMetrics(int metricCount, boolean en) {
        if (metricCount >= 4) {
            return new AtsCheck("quantified_metrics", Status.PASS,
                    en ? "Detected " + metricCount + " quantified metrics (strong)"
                            : "Phát hiện " + metricCount + " chỉ số định lượng (tốt)",
                    "Google XYZ");
        }
        if (metricCount >= 1) {
            return new AtsCheck("quantified_metrics", Status.WARN,
                    en ? "Only " + metricCount + " quantified metric(s) found (aim for ≥ 4)"
                            : "Mới có " + metricCount + " chỉ số định lượng (nên đạt ≥ 4)",
                    "Google XYZ");
        }
        return new AtsCheck("quantified_metrics", Status.FAIL,
                en ? "No quantified metrics found (%, scale, performance)"
                        : "Chưa có số liệu định lượng (%, quy mô, hiệu năng)",
                "Google XYZ");
    }

    private static AtsCheck checkWordCount(int wordCount, boolean en) {
        if (wordCount >= 250 && wordCount <= 900) {
            return new AtsCheck("word_count", Status.PASS,
                    en ? wordCount + " words (optimal 250–900 word density)"
                            : wordCount + " từ (mật độ tối ưu 250–900 từ)",
                    "Harvard OCS");
        }
        if ((wordCount >= 150 && wordCount < 250) || (wordCount > 900 && wordCount <= 1200)) {
            return new AtsCheck("word_count", Status.WARN,
                    en ? wordCount + " words (aim for 250–900 words)"
                            : wordCount + " từ (nên giữ trong khoảng 250–900 từ)",
                    "Harvard OCS");
        }
        return new AtsCheck("word_count", Status.FAIL,
                en ? wordCount + " words (too short or too long for standard length)"
                        : wordCount + " từ (quá ngắn hoặc quá dài so với chuẩn)",
                "Harvard OCS");
    }

    private static AtsCheck checkActionVerbs(String text, boolean en) {
        int active = countMatches(ACTION_VERB, text);
        int passive = countMatches(PASSIVE_PHRASE, text);
        if (active >= 3 && passive <= 1) {
            return new AtsCheck("action_verbs", Status.PASS,
                    en ? "Strong action verbs used (" + active + " verbs)"
                            : "Sử dụng động từ hành động mạnh (" + active + " từ)",
                    "Harvard OCS");
        }
        if (active >= 1) {
            if (passive > 0) {
                return new AtsCheck("action_verbs", Status.WARN,
                        en ? passive + " passive phrase(s) detected; use stronger action verbs"
                                : "Còn " + passive + " cụm từ thụ động, nên dùng động từ mạnh hơn",
                        "Harvard OCS");
            }
            return new AtsCheck("action_verbs", Status.WARN,
                    en ? "Only " + active + " strong action verb(s) found; add more impact verbs"
                            : "Mới có " + active + " động từ hành động mạnh, nên bổ sung thêm",
                    "Harvard OCS");
        }
        return new AtsCheck("action_verbs", Status.FAIL,
                en ? "Lacks strong action verbs or relies on passive phrasing"
                        : "Thiếu động từ hành động mạnh hoặc quá nhiều câu thụ động",
                "Harvard OCS");
    }

    private static int countMatches(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private static boolean containsAny(String haystack, String... needles) {
        for (String n : needles) {
            if (haystack.contains(n)) {
                return true;
            }
        }
        return false;
    }
}
