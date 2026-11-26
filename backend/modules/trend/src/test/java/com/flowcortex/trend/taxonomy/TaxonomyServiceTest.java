package com.flowcortex.trend.taxonomy;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.trend.taxonomy.TaxonomyService.CategoryView;
import com.flowcortex.trend.taxonomy.TaxonomyService.TagView;
import com.flowcortex.trend.taxonomy.TaxonomyService.TaxonomyView;
import com.flowcortex.trend.taxonomy.TaxonomyStore.CategoryRow;
import com.flowcortex.trend.taxonomy.TaxonomyStore.Kind;
import com.flowcortex.trend.taxonomy.TaxonomyStore.TagRow;
import com.flowcortex.trend.web.TrendProblemException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

class TaxonomyServiceTest {

    private final FakeStore store = new FakeStore();

    private static class FakeStore extends TaxonomyStore {

        final List<CategoryRow> categories = new ArrayList<>();
        final List<TagRow> tags = new ArrayList<>();
        boolean foreignKeyOnDelete;
        int reorderCalls;

        FakeStore() {
            super(null);
        }

        FakeStore withCategory(String key) {
            categories.add(new CategoryRow(key, key, key, categories.size(), true));
            return this;
        }

        TagRow withTag() {
            TagRow row = new TagRow(UUID.randomUUID(), "t", "t", tags.size(), true);
            tags.add(row);
            return row;
        }

        @Override
        List<CategoryRow> listCategories() {
            return List.copyOf(categories);
        }

        @Override
        List<TagRow> listTags() {
            return List.copyOf(tags);
        }

        @Override
        int countCategories() {
            return categories.size();
        }

        @Override
        int countTags() {
            return tags.size();
        }

        @Override
        Optional<CategoryRow> insertCategory(String key, String nameVi, String nameEn) {
            if (categories.stream().anyMatch(row -> row.key().equals(key))) {
                return Optional.empty();
            }
            CategoryRow row = new CategoryRow(key, nameVi, nameEn, categories.size(), true);
            categories.add(row);
            return Optional.of(row);
        }

        @Override
        TagRow insertTag(UUID id, String nameVi, String nameEn) {
            TagRow row = new TagRow(id, nameVi, nameEn, tags.size(), true);
            tags.add(row);
            return row;
        }

        @Override
        Optional<CategoryRow> updateCategory(String key, String nameVi, String nameEn, Boolean visible) {
            return categories.stream().filter(row -> row.key().equals(key)).findFirst()
                    .map(row -> new CategoryRow(key, nameVi == null ? row.nameVi() : nameVi,
                            nameEn == null ? row.nameEn() : nameEn, row.order(),
                            visible == null ? row.visible() : visible));
        }

        @Override
        Optional<TagRow> updateTag(UUID id, String nameVi, String nameEn, Boolean visible) {
            return tags.stream().filter(row -> row.id().equals(id)).findFirst()
                    .map(row -> new TagRow(id, nameVi == null ? row.nameVi() : nameVi,
                            nameEn == null ? row.nameEn() : nameEn, row.order(),
                            visible == null ? row.visible() : visible));
        }

        @Override
        boolean reorder(Kind kind, List<String> wanted) {
            reorderCalls++;
            List<String> current = kind == Kind.CATEGORY
                    ? categories.stream().map(CategoryRow::key).toList()
                    : tags.stream().map(row -> row.id().toString()).toList();
            return wanted.size() == current.size() && current.containsAll(wanted);
        }

        @Override
        boolean deleteCategory(String key) {
            if (foreignKeyOnDelete) {
                throw new DataIntegrityViolationException("fk_trend_template_category");
            }
            return categories.removeIf(row -> row.key().equals(key));
        }

        @Override
        boolean deleteTag(UUID id) {
            return tags.removeIf(row -> row.id().equals(id));
        }
    }

    private static class FakeUsage implements TaxonomyUsage {

        final Map<String, Integer> categories;
        final Map<UUID, Integer> tags;
        final AtomicInteger categoryReads = new AtomicInteger();

        FakeUsage(Map<String, Integer> categories, Map<UUID, Integer> tags) {
            this.categories = categories;
            this.tags = tags;
        }

        @Override
        public Map<String, Integer> categoryUsage() {
            return categories;
        }

        @Override
        public Map<UUID, Integer> tagUsage() {
            return tags;
        }

        @Override
        public int categoryUsage(String key) {
            categoryReads.incrementAndGet();
            return categories.getOrDefault(key, 0);
        }
    }

    private TaxonomyService service(TaxonomyUsage... sources) {
        return new TaxonomyService(store, List.of(sources));
    }

    private static TrendProblemException problemOf(Runnable action) {
        try {
            action.run();
        } catch (TrendProblemException e) {
            return e;
        }
        throw new AssertionError("expected a TrendProblemException");
    }

    @Test
    void usageIsTheSumOfSourcesAndZeroWithout() {
        store.withCategory("nguoi").withCategory("san-pham");
        TagRow tag = store.withTag();
        FakeUsage first = new FakeUsage(Map.of("nguoi", 2), Map.of(tag.id(), 1));
        FakeUsage second = new FakeUsage(Map.of("nguoi", 1), Map.of());

        TaxonomyView withSources = service(first, second).list();
        TaxonomyView without = service().list();

        assertThat(withSources.categories()).extracting(CategoryView::usageCount).containsExactly(3, 0);
        assertThat(withSources.tags()).extracting(TagView::usageCount).containsExactly(1);
        assertThat(without.categories()).extracting(CategoryView::usageCount).containsExactly(0, 0);
        assertThat(without.tags()).extracting(TagView::usageCount).containsExactly(0);
    }

    @Test
    void createTriesTheNextCandidateWhenTaken() {
        store.withCategory("thu-cung").withCategory("thu-cung-2");

        CategoryView created = service().createCategory("Thú cưng", "Pets again");

        assertThat(created.key()).isEqualTo("thu-cung-3");
        assertThat(created.nameVi()).isEqualTo("Thú cưng");
        assertThat(created.visible()).isTrue();
        assertThat(created.usageCount()).isZero();
        assertThat(created.order()).isEqualTo(2);
    }

    @Test
    void allCandidatesTakenIs422KeyExhausted() {
        store.withCategory("do-an");
        for (int i = 2; i <= 20; i++) {
            store.withCategory("do-an-" + i);
        }

        TrendProblemException problem = problemOf(() -> service().createCategory("Đồ ăn", "Food"));

        assertThat(problem.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(problem.code()).isEqualTo("category-key-exhausted");
        assertThat(problem.fieldErrors()).containsOnlyKeys("nameVi");
        assertThat(store.categories).hasSize(20);
    }

    @Test
    void nameWithoutLetterOrDigitIsValidationFailedOnNameVi() {
        TrendProblemException problem = problemOf(() -> service().createCategory("!!!", "Symbols"));

        assertThat(problem.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(problem.code()).isEqualTo("validation-failed");
        assertThat(problem.fieldErrors()).containsOnlyKeys("nameVi");
        assertThat(store.categories).isEmpty();
    }

    @Test
    void createAtTheCapIsTaxonomyFull() {
        for (int i = 0; i < TaxonomyService.MAX_RECORDS; i++) {
            store.withCategory("c-" + i);
            store.withTag();
        }

        TrendProblemException category = problemOf(() -> service().createCategory("Mới", "New"));
        TrendProblemException tag = problemOf(() -> service().createTag("Mới", "New"));

        assertThat(category.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(category.code()).isEqualTo("taxonomy-full");
        assertThat(tag.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(tag.code()).isEqualTo("taxonomy-full");
        assertThat(store.categories).hasSize(TaxonomyService.MAX_RECORDS);
        assertThat(store.tags).hasSize(TaxonomyService.MAX_RECORDS);
    }

    @Test
    void reorderOfADifferentSetIsStaleAndChangesNothing() {
        store.withCategory("a").withCategory("b").withCategory("c");
        TagRow first = store.withTag();
        TagRow second = store.withTag();

        TrendProblemException missing = problemOf(() -> service().reorderCategories(List.of("a", "b")));
        TrendProblemException extra = problemOf(() -> service().reorderCategories(List.of("a", "b", "c", "d")));
        TrendProblemException duplicate = problemOf(() -> service().reorderTags(
                List.of(first.id(), first.id())));
        TrendProblemException unknown = problemOf(() -> service().reorderTags(
                List.of(first.id(), UUID.randomUUID())));

        for (TrendProblemException problem : List.of(missing, extra, duplicate, unknown)) {
            assertThat(problem.status()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(problem.code()).isEqualTo("taxonomy-order-stale");
        }
        assertThat(store.listTags()).extracting(TagRow::id).containsExactly(first.id(), second.id());
    }

    @Test
    void aListThatIsTheCurrentSetIsAppliedAndAnswered() {
        store.withCategory("a").withCategory("b");

        List<CategoryView> answered = service().reorderCategories(List.of("b", "a"));

        assertThat(answered).hasSize(2);
        assertThat(store.reorderCalls).isEqualTo(1);
    }

    @Test
    void deleteOfAUsedCategoryIs409WithCount() {
        store.withCategory("nguoi");
        FakeUsage usage = new FakeUsage(Map.of("nguoi", 2), Map.of());

        TrendProblemException problem = problemOf(() -> service(usage).deleteCategory("nguoi"));

        assertThat(problem.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(problem.code()).isEqualTo("category-in-use");
        assertThat(problem.properties()).containsEntry("templateCount", 2);
        assertThat(store.categories).hasSize(1);
    }

    @Test
    void foreignKeyRaceOnDeleteIs409WithOneAndNoRecount() {
        store.withCategory("san-pham");
        store.foreignKeyOnDelete = true;
        FakeUsage usage = new FakeUsage(Map.of(), Map.of());

        TrendProblemException problem = problemOf(() -> service(usage).deleteCategory("san-pham"));

        assertThat(problem.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(problem.code()).isEqualTo("category-in-use");
        assertThat(problem.properties()).containsEntry("templateCount", 1);
        assertThat(usage.categoryReads.get()).isEqualTo(1);
        assertThat(store.categories).hasSize(1);
    }

    @Test
    void anUnusedCategoryAndAnyTagAreDeleted() {
        store.withCategory("thu-cung");
        TagRow tag = store.withTag();

        service().deleteCategory("thu-cung");
        service().deleteTag(tag.id());

        assertThat(store.categories).isEmpty();
        assertThat(store.tags).isEmpty();
    }

    @Test
    void unknownCategoryAndTagAre404() {
        UUID unknown = UUID.randomUUID();

        List<TrendProblemException> problems = List.of(
                problemOf(() -> service().updateCategory("khong-co", "x", null, null)),
                problemOf(() -> service().deleteCategory("khong-co")),
                problemOf(() -> service().updateTag(unknown, "x", null, null)),
                problemOf(() -> service().deleteTag(unknown)));

        assertThat(problems).extracting(TrendProblemException::status).containsOnly(HttpStatus.NOT_FOUND);
        assertThat(problems).extracting(TrendProblemException::code)
                .containsExactly("category-not-found", "category-not-found", "tag-not-found", "tag-not-found");
    }

    @Test
    void aBlankNameOnUpdateIsValidationFailedOnThatField() {
        store.withCategory("nguoi");
        TagRow tag = store.withTag();

        TrendProblemException category = problemOf(() -> service().updateCategory("nguoi", null, " ", null));
        TrendProblemException tagProblem = problemOf(() -> service().updateTag(tag.id(), "", null, true));

        assertThat(category.code()).isEqualTo("validation-failed");
        assertThat(category.fieldErrors()).containsOnlyKeys("nameEn");
        assertThat(tagProblem.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(tagProblem.fieldErrors()).containsOnlyKeys("nameVi");
    }

    @Test
    void aTagUpdateAnswersTheUpdatedRecordWithItsUsage() {
        TagRow tag = store.withTag();
        FakeUsage usage = new FakeUsage(Map.of(), Map.of(tag.id(), 4));

        TagView updated = service(usage).updateTag(tag.id(), null, null, false);

        assertThat(updated.visible()).isFalse();
        assertThat(updated.nameVi()).isEqualTo("t");
        assertThat(updated.usageCount()).isEqualTo(4);
    }
}
