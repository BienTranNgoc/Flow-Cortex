package com.flowcortex.notification.api;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Raises one in-app notification for a member. Publish it with {@code ApplicationEventPublisher}
 * inside the producer's transaction: it is delivered after commit, stored at most once per
 * {@code id}, and never fails the producer after construction. Text is rendered when read from
 * {@code titleCode} / {@code bodyCode} in the message bundles; pass identifiers as strings, or write
 * {@code {n,number,#}} in the message, because a bare {@code {0}} holding a number is grouped by locale.
 *
 * @param link an in-app path such as {@code /workflows}, or null
 * @param args at most 8 strings, numbers or booleans; a string over 200 characters is truncated
 */
public record NotificationRequested(UUID id, UUID memberId, String type, String titleCode, String bodyCode,
        List<Object> args, String link) {

    static final int MAX_ARGS = 8;
    static final int MAX_ARG_LENGTH = 200;
    static final int MAX_LINK_LENGTH = 512;

    private static final Pattern TYPE = Pattern.compile("[a-z][a-z0-9_]{0,63}");
    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9._-]{1,128}");

    public NotificationRequested {
        require(id != null, "id");
        require(memberId != null, "memberId");
        require(type != null && TYPE.matcher(type).matches(), "type");
        require(titleCode != null && CODE.matcher(titleCode).matches(), "titleCode");
        require(bodyCode == null || CODE.matcher(bodyCode).matches(), "bodyCode");
        require(link == null || isInAppPath(link), "link");
        args = copyArgs(args);
    }

    public static NotificationRequested of(UUID memberId, String type, String titleCode) {
        return new NotificationRequested(UUID.randomUUID(), memberId, type, titleCode, null, List.of(), null);
    }

    private static List<Object> copyArgs(List<Object> args) {
        if (args == null) {
            return List.of();
        }
        require(args.size() <= MAX_ARGS, "args");
        List<Object> copy = new ArrayList<>(args.size());
        for (Object arg : args) {
            require(arg instanceof String || arg instanceof Number || arg instanceof Boolean, "args");
            copy.add(arg instanceof String text ? truncate(text) : arg);
        }
        return List.copyOf(copy);
    }

    private static String truncate(String text) {
        return text.length() <= MAX_ARG_LENGTH ? text : text.substring(0, MAX_ARG_LENGTH - 1) + "…";
    }

    private static boolean isInAppPath(String link) {
        if (link.length() > MAX_LINK_LENGTH || !link.startsWith("/") || link.startsWith("//")
                || link.startsWith("/\\")) {
            return false;
        }
        return link.chars().noneMatch(c -> Character.isISOControl(c) || Character.isWhitespace(c));
    }

    private static void require(boolean valid, String field) {
        if (!valid) {
            throw new IllegalArgumentException("Invalid notification " + field);
        }
    }
}
