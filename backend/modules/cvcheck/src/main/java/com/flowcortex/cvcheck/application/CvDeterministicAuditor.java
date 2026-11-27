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
        String text = cvText == null ? "" : cvText.strip();
        String lower = text.toLowerCase(Locale.ROOT);
        int wordCount = text.isEmpty() ? 0 : text.split("\\s+").length;
        int metricCount = countMatches(METRIC, text);

        List<AtsCheck> checks = new ArrayList<>();
        checks.add(checkPageCount(pageCount));
        checks.add(checkContactInfo(text));
        checks.add(checkStandardSections(lower));
        checks.add(checkQuantifiedMetrics(metricCount));
        checks.add(checkWordCount(wordCount));
        checks.add(checkActionVerbs(text));

        return new AuditResult(List.copyOf(checks), metricCount, wordCount, pageCount);
    }

    private static AtsCheck checkPageCount(int pageCount) {
        if (pageCount <= 0) {
            return new AtsCheck("page_count", Status.PASS, "Văn bản trực tiếp (không giới hạn trang PDF)", "Harvard OCS");
        }
        if (pageCount <= 2) {
            return new AtsCheck("page_count", Status.PASS, pageCount + " trang (đạt chuẩn 1–2 trang)", "Harvard OCS");
        }
        if (pageCount == 3) {
            return new AtsCheck("page_count", Status.WARN, "3 trang (nên rút gọn còn 1–2 trang)", "Harvard OCS");
        }
        return new AtsCheck("page_count", Status.FAIL, pageCount + " trang (vượt quá giới hạn chuẩn 2 trang)", "Harvard OCS");
    }

    private static AtsCheck checkContactInfo(String text) {
        boolean hasEmail = EMAIL.matcher(text).find();
        boolean hasPhone = PHONE.matcher(text).find();
        boolean hasLink = PROFILE_LINK.matcher(text).find();
        if (hasEmail && hasPhone && hasLink) {
            return new AtsCheck("contact_info", Status.PASS, "Đầy đủ Email, SĐT và liên kết GitHub/LinkedIn", "SHRM ATS");
        }
        if (hasEmail && hasPhone) {
            return new AtsCheck("contact_info", Status.WARN, "Có Email & SĐT, nên bổ sung GitHub/LinkedIn", "SHRM ATS");
        }
        return new AtsCheck("contact_info", Status.FAIL, "Thiếu Email hoặc Số điện thoại liên hệ", "SHRM ATS");
    }

    private static AtsCheck checkStandardSections(String lower) {
        boolean hasExp = containsAny(lower, "experience", "kinh nghiệm", "projects", "dự án", "work history");
        boolean hasEdu = containsAny(lower, "education", "học vấn", "university", "đại học", "college", "bachelor");
        boolean hasSkills = containsAny(lower, "skills", "kỹ năng", "technologies", "công nghệ", "tech stack");
        int found = (hasExp ? 1 : 0) + (hasEdu ? 1 : 0) + (hasSkills ? 1 : 0);
        if (found == 3) {
            return new AtsCheck("standard_sections", Status.PASS, "Đủ 3 mục cốt lõi (Kinh nghiệm/Dự án, Học vấn, Kỹ năng)", "SHRM ATS");
        }
        if (found == 2) {
            return new AtsCheck("standard_sections", Status.WARN, "Tìm thấy 2/3 mục cốt lõi chuẩn ATS", "SHRM ATS");
        }
        return new AtsCheck("standard_sections", Status.FAIL, "Thiếu các tiêu đề mục chuẩn ATS (" + found + "/3)", "SHRM ATS");
    }

    private static AtsCheck checkQuantifiedMetrics(int metricCount) {
        if (metricCount >= 4) {
            return new AtsCheck("quantified_metrics", Status.PASS, "Phát hiện " + metricCount + " chỉ số định lượng (tốt)", "Google XYZ");
        }
        if (metricCount >= 1) {
            return new AtsCheck("quantified_metrics", Status.WARN, "Mới có " + metricCount + " chỉ số định lượng (nên đạt ≥ 4)", "Google XYZ");
        }
        return new AtsCheck("quantified_metrics", Status.FAIL, "Chưa có số liệu định lượng (%, quy mô, hiệu năng)", "Google XYZ");
    }

    private static AtsCheck checkWordCount(int wordCount) {
        if (wordCount >= 250 && wordCount <= 900) {
            return new AtsCheck("word_count", Status.PASS, wordCount + " từ (mật độ tối ưu 250–900 từ)", "Harvard OCS");
        }
        if ((wordCount >= 150 && wordCount < 250) || (wordCount > 900 && wordCount <= 1200)) {
            return new AtsCheck("word_count", Status.WARN, wordCount + " từ (nên giữ trong khoảng 250–900 từ)", "Harvard OCS");
        }
        return new AtsCheck("word_count", Status.FAIL, wordCount + " từ (quá ngắn hoặc quá dài so với chuẩn)", "Harvard OCS");
    }

    private static AtsCheck checkActionVerbs(String text) {
        int active = countMatches(ACTION_VERB, text);
        int passive = countMatches(PASSIVE_PHRASE, text);
        if (active >= 3 && passive <= 1) {
            return new AtsCheck("action_verbs", Status.PASS, "Sử dụng động từ hành động mạnh (" + active + " từ)", "Harvard OCS");
        }
        if (active >= 1) {
            return new AtsCheck("action_verbs", Status.WARN, "Còn " + passive + " cụm từ thụ động, nên dùng động từ mạnh hơn", "Harvard OCS");
        }
        return new AtsCheck("action_verbs", Status.FAIL, "Thiếu động từ hành động mạnh hoặc quá nhiều câu thụ động", "Harvard OCS");
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
