package com.flowcortex.asset.infrastructure;

import com.flowcortex.asset.domain.AssetCursor;
import com.flowcortex.asset.domain.AssetGroupKeys.Day;
import com.flowcortex.asset.domain.AssetGroupKeys.GroupScope;
import com.flowcortex.asset.domain.AssetGroupKeys.Upload;
import com.flowcortex.asset.domain.AssetGroupKeys.Workflow;
import com.flowcortex.asset.domain.AssetQuery;
import com.flowcortex.asset.domain.AssetQuery.Filters;
import com.flowcortex.asset.domain.AssetQuery.Sort;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/**
 * The SQL fragments every library read shares. User values never appear in a fragment: they are
 * bound as parameters, and the only text concatenated is a fixed column, keyword or checked alias.
 */
final class AssetSql {

    /** The columns of one library item, in the order {@code AssetLibraryQueries} reads them. */
    static final String ITEM_COLUMNS = "%1$s.id, %1$s.kind, %1$s.title, %1$s.source, %1$s.mime_type, %1$s.bytes, "
            + "%1$s.created_at, %1$s.run_id, %1$s.workflow_id, %1$s.workflow_name, %1$s.favorite, %1$s.missing_at, "
            + "%1$s.bucket, %1$s.object_key";

    private static final String LIKE = " LIKE '%' || asset_search_fold(:qEscaped) || '%' ESCAPE '\\'";

    private AssetSql() {}

    /**
     * Whether a row of {@code alias} is one the member can see: the caller's space. The single place
     * the Trash conditions join in; every library read except usage goes through it.
     */
    static String listable(String alias) {
        return alias(alias) + ".space_id = :spaceId";
    }

    /** {@link #ITEM_COLUMNS} for the alias. */
    static String itemColumns(String alias) {
        return ITEM_COLUMNS.formatted(alias(alias));
    }

    /**
     * The conditions of the filters, ANDed, each one starting with {@code " AND "}; binds their
     * parameters. {@code withKind} is false for the counts, which ignore the kind filter.
     */
    static String filters(Filters filters, Instant mark, String alias, boolean withKind,
            MapSqlParameterSource params) {
        String a = alias(alias);
        StringBuilder sql = new StringBuilder(" AND ").append(a).append(".created_at <= :mark");
        params.addValue("mark", Timestamp.from(mark));
        if (withKind && !filters.kinds().isEmpty()) {
            sql.append(" AND ").append(a).append(".kind IN (:kinds)");
            params.addValue("kinds", filters.kinds().stream().map(AssetQuery.Kind::value).toList());
        }
        if (!filters.sources().isEmpty()) {
            sql.append(" AND ").append(a).append(".source IN (:sources)");
            params.addValue("sources", filters.sources().stream().map(AssetQuery.Source::value).toList());
        }
        if (filters.q() != null) {
            params.addValue("qEscaped", AssetQuery.likePattern(filters.q()));
            sql.append(" AND (").append(a).append(".title_search").append(LIKE)
                    .append(" OR EXISTS (SELECT 1 FROM space_asset_tag qt WHERE qt.asset_id = ").append(a)
                    .append(".id AND qt.tag_key").append(LIKE).append("))");
        }
        if (filters.tag() != null) {
            sql.append(" AND EXISTS (SELECT 1 FROM space_asset_tag ft WHERE ft.asset_id = ").append(a)
                    .append(".id AND ft.tag_key = asset_search_fold(:tag))");
            params.addValue("tag", filters.tag());
        }
        if (filters.favorite()) {
            sql.append(" AND ").append(a).append(".favorite");
        }
        time(filters, mark, a, sql, params);
        if (filters.group() != null) {
            sql.append(" AND ").append(scope(filters.group(), a, params));
        }
        if (filters.runId() != null) {
            sql.append(" AND ").append(a).append(".run_id = :runId");
            params.addValue("runId", filters.runId());
        }
        return sql.toString();
    }

    private static void time(Filters filters, Instant mark, String a, StringBuilder sql,
            MapSqlParameterSource params) {
        if (filters.lookback() != null) {
            sql.append(" AND ").append(a).append(".created_at >= :since");
            params.addValue("since", Timestamp.from(mark.minus(filters.lookback())));
        } else if (filters.fromInstant() != null) {
            sql.append(" AND ").append(a).append(".created_at >= :from AND ").append(a)
                    .append(".created_at < :toExclusive");
            params.addValue("from", Timestamp.from(filters.fromInstant()));
            params.addValue("toExclusive", Timestamp.from(filters.toExclusiveInstant()));
        }
    }

    /** The drill-down predicate of one group key: the assets {@code AssetGroupKeys.keyExpression} puts under it. */
    private static String scope(GroupScope scope, String a, MapSqlParameterSource params) {
        return switch (scope) {
            case Workflow workflow -> {
                params.addValue("groupWorkflow", workflow.workflowId());
                yield "(" + a + ".source = 'run' AND " + a + ".workflow_id = :groupWorkflow)";
            }
            case Upload upload -> "NOT (" + a + ".source = 'run' AND " + a + ".workflow_id IS NOT NULL)";
            case Day day -> {
                params.addValue("dayStart", Timestamp.from(day.start()));
                params.addValue("dayEnd", Timestamp.from(day.endExclusive()));
                yield "(" + a + ".created_at >= :dayStart AND " + a + ".created_at < :dayEnd)";
            }
        };
    }

    /** The page order; every direction applies to each column, so the keyset tuple compares one way. */
    static String orderBy(Sort sort, String alias) {
        String a = alias(alias);
        return switch (sort) {
            case NEWEST -> a + ".created_at DESC, " + a + ".id DESC";
            case OLDEST -> a + ".created_at ASC, " + a + ".id ASC";
            case SIZE_DESC -> a + ".bytes DESC, " + a + ".created_at DESC, " + a + ".id DESC";
            case SIZE_ASC -> a + ".bytes ASC, " + a + ".created_at ASC, " + a + ".id ASC";
        };
    }

    /** The condition that keeps only rows after {@code cursor} in the sort's order; empty without a cursor. */
    static String after(AssetCursor cursor, String alias, MapSqlParameterSource params) {
        if (cursor == null) {
            return "";
        }
        String a = alias(alias);
        boolean descending = cursor.sort() == Sort.NEWEST || cursor.sort() == Sort.SIZE_DESC;
        String comparison = descending ? " < " : " > ";
        params.addValue("cursorCreatedAt", Timestamp.from(cursor.createdAt()));
        params.addValue("cursorId", cursor.id());
        if (cursor.sort().bySize()) {
            params.addValue("cursorBytes", cursor.bytes());
            return " AND (" + a + ".bytes, " + a + ".created_at, " + a + ".id)" + comparison
                    + "(:cursorBytes, :cursorCreatedAt, :cursorId)";
        }
        return " AND (" + a + ".created_at, " + a + ".id)" + comparison + "(:cursorCreatedAt, :cursorId)";
    }

    /** Fresh parameters carrying the space every statement is scoped to. */
    static MapSqlParameterSource forSpace(UUID spaceId) {
        return new MapSqlParameterSource("spaceId", spaceId);
    }

    private static String alias(String alias) {
        if (alias == null || !alias.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("Not a SQL identifier: " + alias);
        }
        return alias;
    }
}
