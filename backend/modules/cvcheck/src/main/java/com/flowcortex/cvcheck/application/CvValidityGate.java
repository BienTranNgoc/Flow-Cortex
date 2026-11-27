package com.flowcortex.cvcheck.application;

import java.util.Locale;
import java.util.regex.Pattern;

public final class CvValidityGate {

    private static final int MIN_WORDS = 40;
    private static final int MAX_WORDS = 4_000;
    private static final int MAX_PAGES = 8;
    private static final int MIN_SIGNAL_SCORE = 2;

    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern PHONE = Pattern.compile("(?:\\+?\\d[\\d\\s().-]{7,}\\d)");
    private static final Pattern PROFILE_LINK = Pattern.compile(
            "(?:github\\.com|linkedin\\.com|gitlab\\.com|portfolio)", Pattern.CASE_INSENSITIVE);

    private CvValidityGate() {
    }

    public static boolean looksLikeCv(String cvText, int pageCount) {
        if (cvText == null || cvText.isBlank()) {
            return false;
        }
        if (pageCount > MAX_PAGES) {
            return false;
        }
        String trimmed = cvText.strip();
        int words = trimmed.split("\\s+").length;
        if (words < MIN_WORDS || words > MAX_WORDS) {
            return false;
        }

        String lower = trimmed.toLowerCase(Locale.ROOT);
        int signals = 0;

        if (EMAIL.matcher(trimmed).find() || PHONE.matcher(trimmed).find() || PROFILE_LINK.matcher(trimmed).find()) {
            signals += 2;
        }
        if (containsAny(lower, "education", "học vấn", "university", "đại học", "college", "bachelor", "master",
                "tốt nghiệp", "gpa", "chuyên ngành")) {
            signals += 2;
        }
        if (containsAny(lower, "experience", "kinh nghiệm", "projects", "dự án", "intern", "thực tập",
                "developer", "engineer", "work history", "công ty", "vị trí")) {
            signals += 2;
        }
        if (containsAny(lower, "skills", "kỹ năng", "certifications", "chứng chỉ", "languages", "ngoại ngữ",
                "tech stack", "technologies", "công nghệ")) {
            signals += 2;
        }
        if (containsAny(lower, "curriculum vitae", "resume", "sơ yếu lý lịch", "mục tiêu nghề nghiệp",
                "career objective", "about me", "giới thiệu bản thân", "tổng quan")) {
            signals += 2;
        }

        return signals >= MIN_SIGNAL_SCORE;
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
