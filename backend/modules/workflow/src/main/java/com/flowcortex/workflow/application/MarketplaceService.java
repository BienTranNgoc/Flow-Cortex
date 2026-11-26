package com.flowcortex.workflow.application;

import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.user.api.UserDirectory;
import com.flowcortex.workflow.api.DiscoveryExclusions;
import com.flowcortex.workflow.api.MarketplaceItemView;
import com.flowcortex.workflow.api.WorkflowView;
import com.flowcortex.workflow.application.MarketplaceQuery.Browse;
import com.flowcortex.workflow.domain.Workflow;
import com.flowcortex.workflow.infrastructure.MarketplaceQueries;
import com.flowcortex.workflow.infrastructure.MarketplaceQueries.CatalogueRow;
import com.flowcortex.workflow.infrastructure.WorkflowBookmarkRepository;
import com.flowcortex.workflow.infrastructure.WorkflowRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Discovery reads over the catalogue of published public workflows. The queries pick and order the workflows; the
 * items are then built through the same list mapping as {@code GET /api/v1/workflows}, so a card shows the steps
 * the caller is served.
 */
@Service
public class MarketplaceService {

    public record MarketplacePage(List<MarketplaceItemView> data, long total) {
    }

    public record StepTypeFacet(String type, long count) {
    }

    public record CatalogueStats(long totalWorkflows, long totalBuilders) {
    }

    public record WorkflowCounts(long mine, long saved) {
    }

    private static final int AVERAGE_SCALE = 2;

    private final WorkflowRepository workflows;
    private final MarketplaceQueries queries;
    private final WorkflowServiceImpl views;
    private final WorkflowBookmarkRepository bookmarks;
    private final UserDirectory users;
    private final ObjectProvider<DiscoveryExclusions> exclusions;
    private final Clock clock;

    MarketplaceService(WorkflowRepository workflows, MarketplaceQueries queries, WorkflowServiceImpl views,
            WorkflowBookmarkRepository bookmarks, UserDirectory users, ObjectProvider<DiscoveryExclusions> exclusions,
            Clock clock) {
        this.workflows = workflows;
        this.queries = queries;
        this.views = views;
        this.bookmarks = bookmarks;
        this.users = users;
        this.exclusions = exclusions;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public MarketplacePage browse(UUID callerId, Browse browse) {
        MarketplaceQueries.CataloguePage page = queries.page(browse);
        return new MarketplacePage(items(page.rows(), callerId), page.total());
    }

    @Transactional(readOnly = true)
    public List<MarketplaceItemView> recommended(UUID callerId, int limit) {
        return items(queries.recommended(callerId, limit, clock.instant(), excludedWorkflowIds()), callerId);
    }

    /** Resolved through the visibility rule first, so a workflow the caller cannot read is a 404. */
    @Transactional(readOnly = true)
    public List<MarketplaceItemView> similar(UUID callerId, UUID workflowId, int limit) {
        workflows.findVisibleTo(workflowId, callerId)
                .orElseThrow(() -> new NotFoundException(new LocalizedMessage("workflow.notFound", workflowId)));
        Set<UUID> excluded = excludedWorkflowIds();
        if (excluded.contains(workflowId)) {
            return List.of();
        }
        return items(queries.similar(workflowId, limit, clock.instant(), excluded), callerId);
    }

    @Transactional(readOnly = true)
    public List<StepTypeFacet> facets() {
        return queries.facets().stream().map(facet -> new StepTypeFacet(facet.type(), facet.count())).toList();
    }

    @Transactional(readOnly = true)
    public CatalogueStats stats() {
        MarketplaceQueries.Stats stats = queries.stats();
        return new CatalogueStats(stats.totalWorkflows(), stats.totalBuilders());
    }

    /** {@code saved} is the number the saved list reports as its total. */
    @Transactional(readOnly = true)
    public WorkflowCounts counts(UUID callerId) {
        return new WorkflowCounts(queries.countMine(callerId), bookmarks.countVisible(callerId));
    }

    private Set<UUID> excludedWorkflowIds() {
        Set<UUID> ids = new HashSet<>();
        exclusions.orderedStream().forEach(source -> ids.addAll(source.workflowIds()));
        return ids;
    }

    /** One row per found workflow, in the order of {@code rows}; a row whose workflow vanished meanwhile is dropped. */
    private List<MarketplaceItemView> items(List<CatalogueRow> rows, UUID callerId) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = rows.stream().map(CatalogueRow::workflowId).toList();
        Map<UUID, Workflow> found = workflows.findAllById(ids).stream()
                .collect(Collectors.toMap(Workflow::getId, Function.identity()));
        List<CatalogueRow> present = rows.stream().filter(row -> found.containsKey(row.workflowId())).toList();
        List<WorkflowView> mapped = views.toViews(present.stream().map(row -> found.get(row.workflowId())).toList(),
                callerId);
        Set<UUID> authors = new LinkedHashSet<>();
        for (WorkflowView view : mapped) {
            if (!Workflow.SYSTEM_OWNER_ID.equals(view.ownerId())) {
                authors.add(view.ownerId());
            }
        }
        Map<UUID, String> names = users.findNamesByIds(authors);
        Set<UUID> saved = queries.bookmarkedAmong(callerId, ids);
        return IntStream.range(0, present.size()).mapToObj(i -> {
            CatalogueRow row = present.get(i);
            WorkflowView view = mapped.get(i);
            String author = Workflow.SYSTEM_OWNER_ID.equals(view.ownerId()) ? null : names.get(view.ownerId());
            return new MarketplaceItemView(view, author, average(row), row.ratingCount(),
                    row.membersLast30Days(), saved.contains(row.workflowId()));
        }).toList();
    }

    private static BigDecimal average(CatalogueRow row) {
        return row.ratingCount() == 0 ? null
                : BigDecimal.valueOf(row.ratingSum())
                        .divide(BigDecimal.valueOf(row.ratingCount()), AVERAGE_SCALE, RoundingMode.HALF_UP);
    }
}
