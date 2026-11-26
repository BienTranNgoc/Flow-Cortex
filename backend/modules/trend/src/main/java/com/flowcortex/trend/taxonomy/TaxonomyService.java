package com.flowcortex.trend.taxonomy;

import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.trend.taxonomy.TaxonomyStore.CategoryRow;
import com.flowcortex.trend.taxonomy.TaxonomyStore.Kind;
import com.flowcortex.trend.taxonomy.TaxonomyStore.TagRow;
import com.flowcortex.trend.web.TrendProblemException;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The category and tag catalogue: keys derived from the Vietnamese name, appended order, whole-list reorder,
 * and a delete that a usage source can veto. Logs carry outcomes and counts, never names.
 */
@Service
class TaxonomyService {

    static final int MAX_RECORDS = 1000;

    private static final Logger LOG = LoggerFactory.getLogger(TaxonomyService.class);

    @Schema(name = "AdminTrendCategory")
    record CategoryView(
            @Schema(requiredMode = RequiredMode.REQUIRED) String key,
            @Schema(requiredMode = RequiredMode.REQUIRED) String nameVi,
            @Schema(requiredMode = RequiredMode.REQUIRED) String nameEn,
            @Schema(requiredMode = RequiredMode.REQUIRED) int order,
            @Schema(requiredMode = RequiredMode.REQUIRED) boolean visible,
            @Schema(requiredMode = RequiredMode.REQUIRED) int usageCount) {
    }

    @Schema(name = "AdminTrendTag")
    record TagView(
            @Schema(requiredMode = RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode = RequiredMode.REQUIRED) String nameVi,
            @Schema(requiredMode = RequiredMode.REQUIRED) String nameEn,
            @Schema(requiredMode = RequiredMode.REQUIRED) int order,
            @Schema(requiredMode = RequiredMode.REQUIRED) boolean visible,
            @Schema(requiredMode = RequiredMode.REQUIRED) int usageCount) {
    }

    @Schema(name = "AdminTrendTaxonomy")
    record TaxonomyView(
            @Schema(requiredMode = RequiredMode.REQUIRED) List<CategoryView> categories,
            @Schema(requiredMode = RequiredMode.REQUIRED) List<TagView> tags) {
    }

    private final TaxonomyStore store;
    private final List<TaxonomyUsage> usages;

    TaxonomyService(TaxonomyStore store, List<TaxonomyUsage> usages) {
        this.store = store;
        this.usages = usages;
    }

    @Transactional(readOnly = true)
    TaxonomyView list() {
        return new TaxonomyView(categories(), tags());
    }

    CategoryView createCategory(String nameVi, String nameEn) {
        String base = CategoryKeys.keyOf(nameVi).orElseThrow(TaxonomyService::nameNeedsLetterOrDigit);
        if (store.countCategories() >= MAX_RECORDS) {
            throw full();
        }
        for (String candidate : CategoryKeys.candidates(base)) {
            Optional<CategoryRow> inserted = store.insertCategory(candidate, nameVi, nameEn);
            if (inserted.isPresent()) {
                LOG.info("trend.taxonomy outcome=category_created");
                return view(inserted.get(), 0);
            }
        }
        throw keyExhausted();
    }

    TagView createTag(String nameVi, String nameEn) {
        if (store.countTags() >= MAX_RECORDS) {
            throw full();
        }
        TagRow inserted = store.insertTag(UUID.randomUUID(), nameVi, nameEn);
        LOG.info("trend.taxonomy outcome=tag_created");
        return view(inserted, 0);
    }

    /** A null name or visibility leaves that field as stored; a name that is present must not be blank. */
    CategoryView updateCategory(String key, String nameVi, String nameEn, Boolean visible) {
        requireNotBlank(nameVi, nameEn);
        CategoryRow row = store.updateCategory(key, nameVi, nameEn, visible).orElseThrow(() -> categoryNotFound(key));
        LOG.info("trend.taxonomy outcome=category_updated");
        return view(row, categoryUsage(key));
    }

    TagView updateTag(UUID id, String nameVi, String nameEn, Boolean visible) {
        requireNotBlank(nameVi, nameEn);
        TagRow row = store.updateTag(id, nameVi, nameEn, visible).orElseThrow(() -> tagNotFound(id));
        LOG.info("trend.taxonomy outcome=tag_updated");
        return view(row, tagUsageTotals().getOrDefault(id, 0));
    }

    List<CategoryView> reorderCategories(List<String> keys) {
        reorder(Kind.CATEGORY, keys);
        LOG.info("trend.taxonomy outcome=categories_reordered count={}", keys.size());
        return categories();
    }

    List<TagView> reorderTags(List<UUID> ids) {
        reorder(Kind.TAG, ids.stream().map(UUID::toString).toList());
        LOG.info("trend.taxonomy outcome=tags_reordered count={}", ids.size());
        return tags();
    }

    /**
     * Refused while a usage source counts the category. The foreign key a template adds is the backstop for a
     * reference that lands between the count and the delete: the failed statement aborts the transaction, so
     * that refusal reports one template without counting again.
     */
    void deleteCategory(String key) {
        int used = categoryUsage(key);
        if (used > 0) {
            throw categoryInUse(used);
        }
        boolean deleted;
        try {
            deleted = store.deleteCategory(key);
        } catch (DataIntegrityViolationException e) {
            throw categoryInUse(1);
        }
        if (!deleted) {
            throw categoryNotFound(key);
        }
        LOG.info("trend.taxonomy outcome=category_deleted");
    }

    void deleteTag(UUID id) {
        if (!store.deleteTag(id)) {
            throw tagNotFound(id);
        }
        LOG.info("trend.taxonomy outcome=tag_deleted");
    }

    /** A repeated entry can never be the current set, so it is refused without touching the store. */
    private void reorder(Kind kind, List<String> wanted) {
        if (new HashSet<>(wanted).size() != wanted.size() || !store.reorder(kind, wanted)) {
            throw new TrendProblemException(HttpStatus.CONFLICT, "taxonomy-order-stale",
                    new LocalizedMessage("trend.taxonomy.orderStale"));
        }
    }

    private static void requireNotBlank(String nameVi, String nameEn) {
        Map<String, LocalizedMessage> errors = new LinkedHashMap<>();
        if (nameVi != null && nameVi.isBlank()) {
            errors.put("nameVi", new LocalizedMessage("trend.taxonomy.nameBlank"));
        }
        if (nameEn != null && nameEn.isBlank()) {
            errors.put("nameEn", new LocalizedMessage("trend.taxonomy.nameBlank"));
        }
        if (!errors.isEmpty()) {
            throw new TrendProblemException(HttpStatus.BAD_REQUEST, "validation-failed",
                    new LocalizedMessage("problem.validationFailed")).withFieldErrors(errors);
        }
    }

    private List<CategoryView> categories() {
        Map<String, Integer> usage = categoryUsageTotals();
        return store.listCategories().stream().map(row -> view(row, usage.getOrDefault(row.key(), 0))).toList();
    }

    private List<TagView> tags() {
        Map<UUID, Integer> usage = tagUsageTotals();
        return store.listTags().stream().map(row -> view(row, usage.getOrDefault(row.id(), 0))).toList();
    }

    private Map<String, Integer> categoryUsageTotals() {
        Map<String, Integer> totals = new HashMap<>();
        for (TaxonomyUsage source : usages) {
            source.categoryUsage().forEach((key, count) -> totals.merge(key, count, Integer::sum));
        }
        return totals;
    }

    private Map<UUID, Integer> tagUsageTotals() {
        Map<UUID, Integer> totals = new HashMap<>();
        for (TaxonomyUsage source : usages) {
            source.tagUsage().forEach((id, count) -> totals.merge(id, count, Integer::sum));
        }
        return totals;
    }

    private int categoryUsage(String key) {
        int total = 0;
        for (TaxonomyUsage source : usages) {
            total += source.categoryUsage(key);
        }
        return total;
    }

    private static CategoryView view(CategoryRow row, int usageCount) {
        return new CategoryView(row.key(), row.nameVi(), row.nameEn(), row.order(), row.visible(), usageCount);
    }

    private static TagView view(TagRow row, int usageCount) {
        return new TagView(row.id(), row.nameVi(), row.nameEn(), row.order(), row.visible(), usageCount);
    }

    private static TrendProblemException nameNeedsLetterOrDigit() {
        return new TrendProblemException(HttpStatus.BAD_REQUEST, "validation-failed",
                new LocalizedMessage("problem.validationFailed"))
                .withFieldErrors(Map.of("nameVi", new LocalizedMessage("trend.taxonomy.nameNeedsLetterOrDigit")));
    }

    private static TrendProblemException keyExhausted() {
        return new TrendProblemException(HttpStatus.UNPROCESSABLE_CONTENT, "category-key-exhausted",
                new LocalizedMessage("trend.taxonomy.keyExhausted"))
                .withFieldErrors(Map.of("nameVi", new LocalizedMessage("trend.taxonomy.keyExhausted")));
    }

    private static TrendProblemException full() {
        return new TrendProblemException(HttpStatus.CONFLICT, "taxonomy-full",
                new LocalizedMessage("trend.taxonomy.full", MAX_RECORDS));
    }

    private static TrendProblemException categoryInUse(int templateCount) {
        return new TrendProblemException(HttpStatus.CONFLICT, "category-in-use",
                new LocalizedMessage("trend.taxonomy.categoryInUse", templateCount))
                .with("templateCount", templateCount);
    }

    private static TrendProblemException categoryNotFound(String key) {
        return new TrendProblemException(HttpStatus.NOT_FOUND, "category-not-found",
                new LocalizedMessage("trend.taxonomy.categoryNotFound", key));
    }

    private static TrendProblemException tagNotFound(UUID id) {
        return new TrendProblemException(HttpStatus.NOT_FOUND, "tag-not-found",
                new LocalizedMessage("trend.taxonomy.tagNotFound", id));
    }
}
