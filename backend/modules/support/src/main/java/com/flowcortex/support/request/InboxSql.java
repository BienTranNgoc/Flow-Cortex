package com.flowcortex.support.request;

import com.flowcortex.support.request.InboxQuery.Bounds;
import com.flowcortex.support.request.InboxQuery.InboxFilter;
import com.flowcortex.support.request.InboxQuery.Sort;
import java.sql.Timestamp;
import java.time.Instant;
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

    /**
     * A {@code new} or {@code in_progress} request past its warning cut-off, so due soon or overdue; the cut-offs
     * are {@code now} minus the warning, already collapsed onto the limit when it is not below it.
     */
    static final String AT_RISK = "((status = 'new' AND last_activity_at < :newWarnCut) "
            + "OR (status = 'in_progress' AND last_activity_at < :inProgressWarnCut))";

    private static final String OVERDUE = "((status = 'new' AND last_activity_at < :newCut) "
            + "OR (status = 'in_progress' AND last_activity_at < :inProgressCut))";

    /** The filter without any SLA clause; the filter must not be {@code atRisk}. */
    static Where where(InboxFilter filter, Dimension exclude) {
        return where(filter, exclude, null);
    }

    /**
     * @param thresholds binds the four SLA cut-offs, read from {@code filter.now()}; null only when the filter is
     *        not {@code atRisk} and the query adds no SLA clause of its own
     */
    static Where where(InboxFilter filter, Dimension exclude, SlaThresholds thresholds) {
        List<String> clauses = new ArrayList<>();
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (thresholds != null) {
            Instant now = filter.now();
            params.addValue("newCut", Timestamp.from(now.minus(thresholds.newLimit())))
                    .addValue("newWarnCut", Timestamp.from(now.minus(thresholds.newWarnCut())))
                    .addValue("inProgressCut", Timestamp.from(now.minus(thresholds.inProgressLimit())))
                    .addValue("inProgressWarnCut", Timestamp.from(now.minus(thresholds.inProgressWarnCut())));
        } else if (filter.atRisk()) {
            throw new IllegalArgumentException("The at-risk filter needs the SLA thresholds");
        }
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
        if (filter.atRisk()) {
            clauses.add(AT_RISK);
        }
        return new Where(clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses), params);
    }

    /**
     * Splits the at-risk rows of a query over {@code where.and(AT_RISK)} into overdue and due soon, so a count
     * reads only the rows past a warning cut-off.
     */
    static String slaSummarySelect() {
        return "SELECT count(*) FILTER (WHERE " + OVERDUE + ") AS overdue, count(*) FILTER (WHERE NOT " + OVERDUE
                + ") AS due_soon FROM support_request";
    }

    static String orderBy(Sort sort) {
        return sort == Sort.OLDEST ? " ORDER BY last_activity_at ASC, number ASC"
                : " ORDER BY last_activity_at DESC, number DESC";
    }
}
