package com.flowcortex.support.kb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.flowcortex.support.kb.KnowledgeBaseService.ArticleDraft;
import com.flowcortex.support.web.SupportProblemException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

class KnowledgeBaseServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-19T08:00:00Z");

    private final TopicGroupRepository groups = mock(TopicGroupRepository.class);
    private final ArticleRepository articles = mock(ArticleRepository.class);
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final KnowledgeBaseService service = new KnowledgeBaseService(groups, articles, jdbcTemplate,
            Clock.fixed(NOW, ZoneOffset.UTC));
    private final TopicGroup account = new TopicGroup("Tài khoản", 1, NOW);
    private final TopicGroup credit = new TopicGroup("Credit", 2, NOW);

    @Test
    void groupsWithoutAPublishedArticleAreLeftOut() {
        Article published = article(account, "doi-mat-khau", null);
        published.publish(NOW);
        when(articles.findByStatusOrderByTitleAsc(Article.PUBLISHED)).thenReturn(List.of(published));
        when(groups.findAllByOrderByPositionAscNameAsc()).thenReturn(List.of(account, credit));

        assertThat(service.publishedGroups()).singleElement().satisfies(group -> {
            assertThat(group.name()).isEqualTo("Tài khoản");
            assertThat(group.articles()).extracting(KnowledgeBaseService.ArticleSummary::slug)
                    .containsExactly("doi-mat-khau");
        });
    }

    @Test
    void aDraftIsNotFoundByItsSlug() {
        when(articles.findBySlug("nhap")).thenReturn(Optional.of(article(account, "nhap", null)));

        assertProblem(() -> service.publishedArticle("nhap"), HttpStatus.NOT_FOUND, "article-not-found");
    }

    @Test
    void aPublishedArticleCarriesItsGroupName() {
        Article published = article(credit, "nap-credit", null);
        published.publish(NOW);
        when(articles.findBySlug("nap-credit")).thenReturn(Optional.of(published));
        when(groups.findById(credit.getId())).thenReturn(Optional.of(credit));

        assertThat(service.publishedArticle("nap-credit").groupName()).isEqualTo("Credit");
        assertThat(service.publishedArticle("nap-credit").publishedAt()).isEqualTo(NOW);
    }

    @Test
    void theFeaturedListFollowsThePositions() {
        Article first = article(account, "dau-tien", 1);
        when(articles.findByStatusAndFeaturedPositionNotNullOrderByFeaturedPositionAsc(Article.PUBLISHED))
                .thenReturn(List.of(first));

        assertThat(service.featured()).singleElement().satisfies(featured -> {
            assertThat(featured.slug()).isEqualTo("dau-tien");
            assertThat(featured.position()).isEqualTo(1);
        });
    }

    @Test
    void aQueryWithoutAWordOfTwoCharactersSearchesNothing() {
        assertThat(service.search("a b", 10)).isEmpty();
        assertThat(service.searchHits("nap", 0)).isEmpty();
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void aTakenSlugIsRefused() {
        when(groups.existsById(credit.getId())).thenReturn(true);
        when(articles.existsBySlug("nap-credit")).thenReturn(true);

        assertProblem(() -> service.createArticle(draft(credit, "nap-credit", null)), HttpStatus.CONFLICT,
                "slug-taken");
        verify(articles, never()).saveAndFlush(any());
    }

    @Test
    void anUnknownGroupIsNotFound() {
        assertProblem(() -> service.createArticle(draft(credit, "nap-credit", null)), HttpStatus.NOT_FOUND,
                "group-not-found");
    }

    @Test
    void aFourthStartArticleIsRefusedAsFull() {
        when(groups.existsById(account.getId())).thenReturn(true);
        when(articles.findByFeaturedPositionNotNull()).thenReturn(List.of(
                article(account, "a", 1), article(account, "b", 2), article(account, "c", 3)));

        assertProblem(() -> service.createArticle(draft(account, "d", 2)), HttpStatus.CONFLICT, "featured-full");
    }

    @Test
    void aHeldPositionWithAFreeSlotIsRefusedAsTaken() {
        when(groups.existsById(account.getId())).thenReturn(true);
        when(articles.findByFeaturedPositionNotNull()).thenReturn(List.of(article(account, "a", 1)));

        assertProblem(() -> service.createArticle(draft(account, "d", 1)), HttpStatus.CONFLICT,
                "featured-position-taken");
    }

    @Test
    void aFreePositionIsTakenAndTheArticleStartsAsADraft() {
        when(groups.existsById(account.getId())).thenReturn(true);
        when(articles.findByFeaturedPositionNotNull()).thenReturn(List.of(article(account, "a", 1)));

        KnowledgeBaseService.AdminArticle created = service.createArticle(draft(account, "d", 2));

        assertThat(created.status()).isEqualTo("draft");
        assertThat(created.featuredPosition()).isEqualTo(2);
        verify(articles).saveAndFlush(any());
    }

    @Test
    void keepingItsOwnPositionIsNotAConflict() {
        Article own = article(account, "a", 1);
        when(articles.findById(own.getId())).thenReturn(Optional.of(own));
        when(groups.existsById(account.getId())).thenReturn(true);
        when(articles.findByFeaturedPositionNotNull()).thenReturn(List.of(own));

        assertThat(service.updateArticle(own.getId(), draft(account, "a", 1)).featuredPosition()).isEqualTo(1);
    }

    @Test
    void anArchivedArticleCannotTakeAPosition() {
        Article archived = article(account, "cu", null);
        archived.archive(NOW);
        when(articles.findById(archived.getId())).thenReturn(Optional.of(archived));
        when(groups.existsById(account.getId())).thenReturn(true);

        assertProblem(() -> service.updateArticle(archived.getId(), draft(account, "cu", 1)), HttpStatus.BAD_REQUEST,
                "validation-failed");
    }

    @Test
    void archivingFreesThePositionAndPublishingStampsTheTime() {
        Article article = article(account, "bat-dau", 2);
        when(articles.findById(article.getId())).thenReturn(Optional.of(article));

        assertThat(service.publish(article.getId()).publishedAt()).isEqualTo(NOW);
        KnowledgeBaseService.AdminArticle archived = service.archive(article.getId());

        assertThat(archived.status()).isEqualTo("archived");
        assertThat(archived.featuredPosition()).isNull();
    }

    @Test
    void aGroupHoldingArticlesIsNotDeleted() {
        when(groups.findById(credit.getId())).thenReturn(Optional.of(credit));
        when(articles.countByGroupId(credit.getId())).thenReturn(1L);

        assertThatThrownBy(() -> service.deleteGroup(credit.getId()))
                .isInstanceOfSatisfying(SupportProblemException.class, e -> {
                    assertThat(e.code()).isEqualTo("group-not-empty");
                    assertThat(e.properties()).containsEntry("articleCount", 1L);
                });
        verify(groups, never()).delete(any());
    }

    @Test
    void anEmptyGroupIsDeleted() {
        when(groups.findById(credit.getId())).thenReturn(Optional.of(credit));

        service.deleteGroup(credit.getId());

        verify(groups).delete(credit);
    }

    @Test
    void aTakenGroupNameIsRefused() {
        when(groups.existsByName("Credit")).thenReturn(true);

        assertProblem(() -> service.createGroup("Credit"), HttpStatus.CONFLICT, "group-name-taken");
    }

    @Test
    void aNewGroupGoesLast() {
        when(groups.maxPosition()).thenReturn(4);

        assertThat(service.createGroup("Mới").position()).isEqualTo(5);
    }

    @Test
    void renamingKeepsTheCount() {
        when(groups.findById(credit.getId())).thenReturn(Optional.of(credit));
        when(articles.countByGroupId(credit.getId())).thenReturn(3L);

        KnowledgeBaseService.AdminGroup renamed = service.renameGroup(credit.getId(), "Credit & ví");

        assertThat(renamed.name()).isEqualTo("Credit & ví");
        assertThat(renamed.articleCount()).isEqualTo(3);
    }

    @Test
    void theOrderMustNameEveryGroupOnce() {
        when(groups.findAll()).thenReturn(List.of(account, credit));

        assertProblem(() -> service.reorderGroups(List.of(account.getId())), HttpStatus.BAD_REQUEST,
                "group-order-mismatch");
        assertProblem(() -> service.reorderGroups(List.of(account.getId(), account.getId())), HttpStatus.BAD_REQUEST,
                "group-order-mismatch");
    }

    @Test
    void reorderingRenumbersFromOne() {
        when(groups.findAll()).thenReturn(List.of(account, credit));

        service.reorderGroups(List.of(credit.getId(), account.getId()));

        assertThat(credit.getPosition()).isEqualTo(1);
        assertThat(account.getPosition()).isEqualTo(2);
    }

    @Test
    void theAdminListCountsPublishedStartArticles() {
        when(groups.findAll()).thenReturn(List.of(account));
        when(articles.findAllByOrderByUpdatedAtDesc()).thenReturn(List.of(article(account, "a", 1)));
        when(articles.countByStatusAndFeaturedPositionNotNull(Article.PUBLISHED)).thenReturn(2L);
        when(groups.findAllByOrderByPositionAscNameAsc()).thenReturn(List.of(account));
        when(articles.countByGroup()).thenReturn(List.<Object[]>of(new Object[] {account.getId(), 1L}));

        KnowledgeBaseService.AdminArticleList list = service.adminArticles();

        assertThat(list.publishedFeaturedCount()).isEqualTo(2);
        assertThat(list.items()).singleElement().satisfies(item -> assertThat(item.groupName()).isEqualTo("Tài khoản"));
        assertThat(service.adminGroups()).singleElement().satisfies(group -> assertThat(group.articleCount()).isEqualTo(1));
    }

    @Test
    void anUnknownArticleIsNotFoundForTheAdmin() {
        UUID id = UUID.randomUUID();

        assertProblem(() -> service.adminArticle(id), HttpStatus.NOT_FOUND, "article-not-found");
    }

    private static Article article(TopicGroup group, String slug, Integer position) {
        return new Article(group.getId(), slug, "Tiêu đề " + slug, "Nội dung", position == null ? null
                : position.shortValue(), NOW);
    }

    private static ArticleDraft draft(TopicGroup group, String slug, Integer position) {
        return new ArticleDraft(group.getId(), slug, "Tiêu đề", "Nội dung", position);
    }

    private static void assertProblem(Runnable call, HttpStatus status, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(SupportProblemException.class, e -> {
            assertThat(e.status()).isEqualTo(status);
            assertThat(e.code()).isEqualTo(code);
        });
    }
}
