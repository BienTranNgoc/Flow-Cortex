package com.flowcortex.asset.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The one rule that puts an asset in a group: {@code workflow:<id>} for a run asset with a workflow,
 * {@code upload} for any other, {@code day:<YYYY-MM-DD>} of the created time in Vietnam. The same
 * rule is written as a SQL expression for grouping and parsed back into a scope for the drill-down,
 * so a group's count and the list opened from it cannot disagree.
 */
public final class AssetGroupKeys {

    /** The zone whose calendar days group assets and bound custom ranges. */
    public static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    static final String PARAMETER = "group";

    private static final String WORKFLOW_PREFIX = "workflow:";
    private static final String DAY_PREFIX = "day:";
    private static final String UPLOAD = "upload";
    private static final Pattern UUID_TEXT =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern DAY_TEXT = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private AssetGroupKeys() {}

    /** How the groups route buckets assets. */
    public enum GroupBy {
        WORKFLOW,
        DAY;

        public String value() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** The assets one group key stands for. */
    public sealed interface GroupScope permits Workflow, Upload, Day {

        /** The canonical key text. */
        String key();
    }

    /** Run assets of one workflow. */
    public record Workflow(UUID workflowId) implements GroupScope {

        @Override
        public String key() {
            return WORKFLOW_PREFIX + workflowId;
        }
    }

    /** Uploads, and run assets that name no workflow. */
    public record Upload() implements GroupScope {

        @Override
        public String key() {
            return UPLOAD;
        }
    }

    /** Assets created on one Vietnam calendar day. */
    public record Day(LocalDate day) implements GroupScope {

        @Override
        public String key() {
            return DAY_PREFIX + day;
        }

        /** First instant of the day. */
        public Instant start() {
            return day.atStartOfDay(ZONE).toInstant();
        }

        /** First instant of the next day. */
        public Instant endExclusive() {
            return day.plusDays(1).atStartOfDay(ZONE).toInstant();
        }
    }

    /** The key of one asset under the given grouping; {@code workflowId} may be null. */
    public static String keyOf(GroupBy groupBy, String source, UUID workflowId, Instant createdAt) {
        if (groupBy == GroupBy.DAY) {
            return new Day(createdAt.atZone(ZONE).toLocalDate()).key();
        }
        boolean inWorkflow = "run".equals(source) && workflowId != null;
        return inWorkflow ? new Workflow(workflowId).key() : new Upload().key();
    }

    /** Parses a {@code group} request parameter; refuses a key {@link #keyOf} cannot produce. */
    public static GroupScope parse(String key) {
        if (key == null) {
            throw new AssetRequestRejected(PARAMETER);
        }
        if (key.equals(UPLOAD)) {
            return new Upload();
        }
        if (key.startsWith(WORKFLOW_PREFIX)) {
            String id = key.substring(WORKFLOW_PREFIX.length());
            if (UUID_TEXT.matcher(id).matches()) {
                return new Workflow(UUID.fromString(id));
            }
        } else if (key.startsWith(DAY_PREFIX)) {
            String day = key.substring(DAY_PREFIX.length());
            if (DAY_TEXT.matcher(day).matches()) {
                try {
                    return new Day(LocalDate.parse(day));
                } catch (DateTimeParseException e) {
                    throw new AssetRequestRejected(PARAMETER);
                }
            }
        }
        throw new AssetRequestRejected(PARAMETER);
    }

    /**
     * The SQL expression computing the key of a {@code space_asset} row under the given grouping.
     * The alias is checked as a plain identifier because it is concatenated, never bound.
     */
    public static String keyExpression(GroupBy groupBy, String alias) {
        if (alias == null || !IDENTIFIER.matcher(alias).matches()) {
            throw new IllegalArgumentException("Not a SQL identifier: " + alias);
        }
        if (groupBy == GroupBy.DAY) {
            return "'day:' || to_char(" + alias + ".created_at AT TIME ZONE '" + ZONE.getId() + "', 'YYYY-MM-DD')";
        }
        return "CASE WHEN " + alias + ".source = 'run' AND " + alias + ".workflow_id IS NOT NULL"
                + " THEN 'workflow:' || " + alias + ".workflow_id::text ELSE 'upload' END";
    }
}
