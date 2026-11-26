package com.flowcortex.chat.conversation;

import com.flowcortex.chat.conversation.ListCursor.Sort;
import com.flowcortex.chat.web.ChatProblemException;
import com.flowcortex.shared.web.LocalizedMessage;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;

/**
 * One list request after validation: page size, sort, the cursor, the pinned and folder filters and the search
 * words. {@link #parse} is the only way in, so every field the store sees is already checked.
 */
public record ConversationQuery(int limit, Sort sort, ListCursor cursor, Pinned pinned, FolderFilter folder,
        List<String> words) {

    public static final int DEFAULT_LIMIT = 30;
    public static final int MIN_LIMIT = 1;
    public static final int MAX_LIMIT = 100;
    public static final int MAX_QUERY_CHARS = 100;
    public static final int MAX_WORDS = 8;

    private static final Pattern UUID_TEXT = Pattern.compile("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}");
    private static final String SEPARATOR = "[\\p{javaWhitespace}\\p{Z}]+";
    private static final Pattern SEPARATORS = Pattern.compile(SEPARATOR);
    private static final Pattern EDGE_SEPARATORS = Pattern.compile("^" + SEPARATOR + "|" + SEPARATOR + "$");

    /** Which conversations the list keeps by their pinned flag. */
    public enum Pinned { ANY, ONLY, EXCLUDE }

    /** Which conversations the list keeps by folder: any, none (Recent) or one folder. */
    public record FolderFilter(boolean none, UUID id) {

        public static final FolderFilter ANY = new FolderFilter(false, null);
        public static final FolderFilter NONE = new FolderFilter(true, null);
    }

    public ConversationQuery {
        words = List.copyOf(words);
    }

    /**
     * Every argument is the raw query parameter, {@code null} when absent. Refusals are collected and thrown as
     * one 400 {@code validation-failed} with a field error per refused parameter.
     */
    public static ConversationQuery parse(String limit, String cursor, String sort, String pinned, String folder,
            String q) {
        Map<String, LocalizedMessage> errors = new LinkedHashMap<>();
        int parsedLimit = limit(limit, errors);
        Sort parsedSort = sort(sort, errors);
        ListCursor parsedCursor = null;
        if (cursor != null && parsedSort != null) {
            parsedCursor = ListCursor.decode(cursor, parsedSort).orElse(null);
            if (parsedCursor == null) {
                errors.put("cursor", new LocalizedMessage("chat.list.cursor.invalid"));
            }
        }
        Pinned parsedPinned = pinned(pinned, errors);
        FolderFilter parsedFolder = folder(folder, errors);
        List<String> words = words(q, errors);
        if (!errors.isEmpty()) {
            throw new ChatProblemException(HttpStatus.BAD_REQUEST, "validation-failed",
                    new LocalizedMessage("problem.validationFailed")).withFieldErrors(errors);
        }
        return new ConversationQuery(parsedLimit, parsedSort, parsedCursor, parsedPinned, parsedFolder, words);
    }

    private static int limit(String raw, Map<String, LocalizedMessage> errors) {
        if (raw == null) {
            return DEFAULT_LIMIT;
        }
        if (raw.matches("[0-9]{1,3}")) {
            int value = Integer.parseInt(raw);
            if (value >= MIN_LIMIT && value <= MAX_LIMIT) {
                return value;
            }
        }
        errors.put("limit", new LocalizedMessage("chat.list.limit.invalid", MIN_LIMIT, MAX_LIMIT));
        return DEFAULT_LIMIT;
    }

    private static Sort sort(String raw, Map<String, LocalizedMessage> errors) {
        if (raw == null || "newest".equals(raw)) {
            return Sort.NEWEST;
        }
        if ("oldest".equals(raw)) {
            return Sort.OLDEST;
        }
        errors.put("sort", new LocalizedMessage("chat.list.filter.invalid"));
        return null;
    }

    private static Pinned pinned(String raw, Map<String, LocalizedMessage> errors) {
        if (raw == null) {
            return Pinned.ANY;
        }
        return switch (raw) {
            case "only" -> Pinned.ONLY;
            case "exclude" -> Pinned.EXCLUDE;
            default -> {
                errors.put("pinned", new LocalizedMessage("chat.list.filter.invalid"));
                yield Pinned.ANY;
            }
        };
    }

    private static FolderFilter folder(String raw, Map<String, LocalizedMessage> errors) {
        if (raw == null) {
            return FolderFilter.ANY;
        }
        if ("none".equals(raw)) {
            return FolderFilter.NONE;
        }
        if (UUID_TEXT.matcher(raw).matches()) {
            return new FolderFilter(false, UUID.fromString(raw));
        }
        errors.put("folder", new LocalizedMessage("chat.list.filter.invalid"));
        return FolderFilter.ANY;
    }

    /**
     * Control characters removed (whitespace ones still separate words), trimmed, at most 100 characters; the words
     * are the first 8 distinct ones ignoring case, NFC-normalized so a decomposed query still folds in SQL.
     */
    private static List<String> words(String raw, Map<String, LocalizedMessage> errors) {
        if (raw == null) {
            return List.of();
        }
        StringBuilder cleaned = new StringBuilder();
        Normalizer.normalize(raw, Normalizer.Form.NFC).codePoints()
                .filter(cp -> Character.getType(cp) != Character.CONTROL || Character.isWhitespace(cp))
                .forEach(cleaned::appendCodePoint);
        String trimmed = EDGE_SEPARATORS.matcher(cleaned).replaceAll("");
        if (trimmed.codePointCount(0, trimmed.length()) > MAX_QUERY_CHARS) {
            errors.put("q", new LocalizedMessage("chat.list.q.tooLong", MAX_QUERY_CHARS));
            return List.of();
        }
        List<String> words = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String word : SEPARATORS.split(trimmed)) {
            if (!word.isEmpty() && words.size() < MAX_WORDS && seen.add(word.toLowerCase(Locale.ROOT))) {
                words.add(word);
            }
        }
        return words;
    }
}
