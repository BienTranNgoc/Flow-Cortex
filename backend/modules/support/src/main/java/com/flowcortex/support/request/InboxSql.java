package com.flowcortex.support.request;

import com.flowcortex.support.request.InboxQuery.Bounds;
import com.flowcortex.support.request.InboxQuery.InboxFilter;
import com.flowcortex.support.request.InboxQuery.Sort;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/**
 * The one WHERE the inbox list, its total and every count are built from; values only ever travel as named
 * parameters. A count passes its own dimension as {@code exclude}, so a selection never narrows its own counts.
 */
final class InboxSql {

    enum Dimension {
        STATUS, TYPE, ASSIGNEE, CREATED
    }

    /** {@code sql} is empty or starts with {@code " WHERE "}. */
    record Where(String sql, MapSqlParameterSource params) {

        /** The same condition with one more clause, for a query that adds its own. */
        String and(String clause) {
            return sql.isEmpty() ? " WHERE " + clause : sql + " AND " + clause;
        }
    }

    private InboxSql() {
    }

    static Where where(InboxFilter filter, Dimension exclude) {
        List<String> clauses = new ArrayList<>();
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (filter.status() != null && exclude != Dimension.STATUS) {
            clauses.add("status = :status");
            params.addValue("status", filter.status());
        }
        if (filter.type() != null && exclude != Dimension.TYPE) {
            clauses.add("type = :type");
            params.addValue("type", filter.type());
        }
        if (filter.assignee() != null && exclude != Dimension.ASSIGNEE) {
            if (filter.assignee().unassigned()) {
                clauses.add("assignee_id IS NULL");
            } else {
                clauses.add("assignee_id = :assignee");
                params.addValue("assignee", filter.assignee().id());
            }
        }
        if (filter.created() != null && exclude != Dimension.CREATED) {
            Bounds bounds = filter.created().bounds(filter.now());
            if (bounds.after() != null) {
                clauses.add("created_at > :createdAfter");
                params.addValue("createdAfter", Timestamp.from(bounds.after()));
            }
            if (bounds.atOrBefore() != null) {
                clauses.add("created_at <= :createdAtOrBefore");
                params.addValue("createdAtOrBefore", Timestamp.from(bounds.atOrBefore()));
            }
        }
        if (filter.search() != null) {
            params.addValue("pattern", filter.search().pattern());
            if (filter.search().number() != null) {
                clauses.add("(lower(subject) LIKE lower(:pattern) OR number = :number)");
                params.addValue("number", filter.search().number());
            } else {
                clauses.add("lower(subject) LIKE lower(:pattern)");
            }
        }
        return new Where(clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses), params);
    }

    static String orderBy(Sort sort) {
        return sort == Sort.OLDEST ? " ORDER BY last_activity_at ASC, number ASC"
                : " ORDER BY last_activity_at DESC, number DESC";
    }
}
