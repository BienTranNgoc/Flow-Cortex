package com.flowcortex.support.assistant;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The editable support request proposed with a handoff, built only from what the member typed: the first
 * message that carries intent is the subject, the messages are the description, and the codes they typed
 * (run ids, UUIDs, long numbers) follow it so none is invented or lost to the cut.
 */
final class HandoffDraft {

    /** How many of the latest user messages feed a draft. */
    static final int WINDOW = 10;

    private static final int SUBJECT_MIN = 5;
    private static final int SUBJECT_MAX = 120;
    private static final int DESCRIPTION_MAX = 1800;
    private static final int CODES_MAX = 20;
    private static final String CODES_LABEL_VI = "Mã liên quan: ";
    private static final String CODES_LABEL_EN = "Related codes/IDs: ";

    /** A UUID, a prefix-and-digits code such as RUN-4821, or a run of 8 or more digits; the UUID is tried first. */
    private static final Pattern CODE = Pattern.compile(
            "\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b"
                    + "|\\b[A-Za-z]{2,6}-[A-Za-z0-9]*\\d[A-Za-z0-9]*\\b"
                    + "|\\b\\d{8,}\\b");

    /** Greeting and filler words, folded, that carry no support intent alone. */
    private static final Set<String> LOW_INTENT_WORDS = Set.of(
            "chao", "xin", "ban", "alo", "lo", "oi", "cam", "on", "nhe",
            "hello", "hi", "hey", "yo", "thanks", "thank", "ok", "oke", "okay");

    private HandoffDraft() {
    }

    /** The draft: {@code subject} is absent when the first kept message is under 5 characters. */
    record Draft(Optional<String> subject, String description) {
    }

    /** Builds the draft from the user's messages in order, oldest first; the latest {@link #WINDOW} are used. */
    static Draft build(List<String> userMessages, Locale locale) {
        List<String> all = userMessages.stream().map(String::strip).filter(message -> !message.isEmpty()).toList();
        List<String> window = all.subList(Math.max(0, all.size() - WINDOW), all.size());
        if (window.isEmpty()) {
            return new Draft(Optional.empty(), "");
        }
        List<String> kept = window.stream().filter(message -> !lowIntent(message)).toList();
        if (kept.isEmpty()) {
            kept = window;
        }
        String subject = cut(kept.get(0).replaceAll("\\s+", " "), SUBJECT_MAX).stripTrailing();
        String description = cut(String.join("\n", kept), DESCRIPTION_MAX).stripTrailing();
        List<String> codes = codes(String.join("\n", window));
        if (!codes.isEmpty()) {
            String label = "vi".equals(locale.getLanguage()) ? CODES_LABEL_VI : CODES_LABEL_EN;
            description += "\n\n" + label + String.join(", ", codes);
        }
        boolean longEnough = subject.codePointCount(0, subject.length()) >= SUBJECT_MIN;
        return new Draft(longEnough ? Optional.of(subject) : Optional.empty(), description);
    }

    /** The codes in the text, in order of appearance, deduplicated ignoring case, at most {@value #CODES_MAX}. */
    static List<String> codes(String text) {
        List<String> codes = new ArrayList<>();
        if (text == null) {
            return codes;
        }
        Set<String> seen = new HashSet<>();
        Matcher matcher = CODE.matcher(text);
        while (matcher.find() && codes.size() < CODES_MAX) {
            if (seen.add(matcher.group().toLowerCase(Locale.ROOT))) {
                codes.add(matcher.group());
            }
        }
        return codes;
    }

    /** A greeting or small talk: every token of two characters or more, folded, is a filler word. */
    static boolean lowIntent(String message) {
        return Arrays.stream(TextFold.fold(message).split("[^\\p{L}\\p{N}]+"))
                .filter(token -> token.length() >= 2)
                .allMatch(LOW_INTENT_WORDS::contains);
    }

    private static String cut(String text, int maxCodePoints) {
        if (text.codePointCount(0, text.length()) <= maxCodePoints) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, maxCodePoints));
    }
}
