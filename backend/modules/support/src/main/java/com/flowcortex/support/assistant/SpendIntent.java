package com.flowcortex.support.assistant;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Whether a guest's message asks the assistant to produce something that spends credits: a creation verb followed
 * closely by a media or workflow noun, in Vietnamese (accented or not) or English. A how-to, price, where-is,
 * yes/no or troubleshooting question is a help question even when it names the same words.
 */
final class SpendIntent {

    /** How many words may sit between the verb and its noun ("make for me one clip"). */
    private static final int NOUN_WINDOW = 5;

    private static final List<List<String>> VERBS = phrases(
            "lam", "tao", "san xuat", "dung", "ve", "thiet ke", "chay",
            "make", "create", "generate", "design", "render", "produce", "build", "draw");

    private static final List<List<String>> NOUNS = phrases(
            "video", "clip", "hinh anh", "hinh", "anh", "banner", "poster", "logo", "thumbnail", "slide",
            "workflow", "quy trinh", "nhac", "bai hat", "am thanh", "giong doc", "voice", "podcast",
            "image", "picture", "photo", "music", "song", "audio", "deck", "presentation");

    /** Folded, space-delimited phrases that make the message a help question. */
    private static final List<String> HELP_CUES = List.of(
            "lam sao", "lam the nao", "the nao", "nhu the nao", "cach", "huong dan", "o dau", "bao nhieu", "gia",
            "phi", "how", "where", "what", "why", "tutorial", "guide", "price", "cost", "loi", "ho tro", "yeu cau",
            "gi");

    /** Folded single words that collide with another word; in an accented message only the accented spelling counts. */
    private static final Map<String, String> ACCENTED_ONLY = Map.of("anh", "ảnh", "dung", "dựng");

    private SpendIntent() {
    }

    static boolean isSpendRequest(String question) {
        List<String> original = tokens(TextFold.lower(question));
        List<String> folded = original.stream().map(TextFold::fold).toList();
        if (folded.isEmpty() || isHelpQuestion(folded)) {
            return false;
        }
        boolean accented = false;
        for (int i = 0; i < folded.size(); i++) {
            accented |= !original.get(i).equals(folded.get(i));
        }
        for (int i = 0; i < folded.size(); i++) {
            int verb = phraseAt(folded, i, VERBS);
            if (verb == 0 || !isAccentedForm(original, folded, i, accented)) {
                continue;
            }
            // The run verb also narrates an ongoing or past run; only an imperative opening asks for one.
            if ("chay".equals(folded.get(i)) && i != 0) {
                continue;
            }
            int from = i + verb;
            int to = Math.min(folded.size(), from + NOUN_WINDOW);
            for (int j = from; j < to; j++) {
                if (isNounAt(original, folded, j, accented)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isHelpQuestion(List<String> folded) {
        String text = " " + String.join(" ", folded) + " ";
        if (HELP_CUES.stream().anyMatch(cue -> text.contains(" " + cue + " "))) {
            return true;
        }
        int yesNo = folded.indexOf("co");
        return yesNo != -1 && folded.lastIndexOf("khong") > yesNo;
    }

    private static boolean isAccentedForm(List<String> original, List<String> folded, int at, boolean accented) {
        String required = ACCENTED_ONLY.get(folded.get(at));
        return required == null || !accented || original.get(at).equals(required);
    }

    private static boolean isNounAt(List<String> original, List<String> folded, int at, boolean accented) {
        int length = phraseAt(folded, at, NOUNS);
        if (length == 0) {
            return false;
        }
        return length > 1 || isAccentedForm(original, folded, at, accented);
    }

    /** Length of the first phrase of {@code list} that starts at {@code at}, or 0. */
    private static int phraseAt(List<String> words, int at, List<List<String>> list) {
        for (List<String> phrase : list) {
            if (at + phrase.size() <= words.size() && words.subList(at, at + phrase.size()).equals(phrase)) {
                return phrase.size();
            }
        }
        return 0;
    }

    private static List<String> tokens(String lower) {
        return Arrays.stream(lower.split("[^\\p{L}\\p{N}]+")).filter(token -> !token.isEmpty()).toList();
    }

    private static List<List<String>> phrases(String... words) {
        return Stream.of(words).map(word -> List.of(word.split(" "))).toList();
    }
}
