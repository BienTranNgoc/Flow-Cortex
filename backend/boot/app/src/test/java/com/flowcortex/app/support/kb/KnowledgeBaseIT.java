package com.flowcortex.app.support.kb;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.AbstractPostgresBootTest;
import com.flowcortex.user.api.UserService;
import com.flowcortex.user.api.UserView;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * The knowledge base over HTTP with the Help Center on. Every case starts from an empty knowledge
 * base, since "nothing published" and "at most 10 results" depend on what else is stored.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
        "flowcortex.support.enabled=true",
        "flowcortex.support.contact-email=hotro@nodi.vn"})
@AutoConfigureTestRestTemplate
class KnowledgeBaseIT extends AbstractPostgresBootTest {

    private static final URI PROBLEMS = URI.create("https://flow-cortex.dev/problems/");

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserService userService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String adminToken;

    record LoginRequest(String email, String password) {
    }

    record TokenResponse(String accessToken) {
    }

    record ArticleSummary(String slug, String title) {
    }

    record GroupView(UUID id, String name, int position, List<ArticleSummary> articles) {
    }

    record ArticleView(String slug, String title, String body, UUID groupId, String groupName, Instant publishedAt) {
    }

    record FeaturedView(String slug, String title, int position) {
    }

    record SearchHit(String slug, String title, String groupName) {
    }

    record GroupRequest(String name) {
    }

    record GroupOrderRequest(List<UUID> ids) {
    }

    record AdminGroupView(UUID id, String name, int position, long articleCount) {
    }

    record ArticleRequest(UUID groupId, String slug, String title, String body, Integer featuredPosition) {
    }

    record AdminArticleView(UUID id, String slug, String title, String body, String status, UUID groupId,
            Integer featuredPosition, Instant publishedAt) {
    }

    record AdminArticleList(List<AdminArticleView> items, int publishedFeaturedCount) {
    }

    record Problem(int status, URI type, String detail, Long articleCount) {
    }

    record SettingsView(String contactEmail, Boolean assistantAvailable) {
    }

    @BeforeEach
    void emptyKnowledgeBase() {
        jdbcTemplate.update("DELETE FROM support_article");
        jdbcTemplate.update("DELETE FROM support_topic_group");
        UserView admin = userService.createInitialAdmin(uniqueEmail("kb-admin"), "password123", "KB Admin");
        adminToken = login(admin.email());
    }

    @Test
    void aPublishedArticleOpensForASignedOutVisitor() {
        UUID group = createGroup("Credit");
        publish(createArticle(group, "nap-credit", "Nạp credit", "Vào **Ví** và chọn gói.", null));

        ResponseEntity<ArticleView> response = exchange(HttpMethod.GET, "/api/v1/support/kb/articles/nap-credit",
                null, null, ArticleView.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().title()).isEqualTo("Nạp credit");
        assertThat(response.getBody().body()).isEqualTo("Vào **Ví** và chọn gói.");
        assertThat(response.getBody().groupName()).isEqualTo("Credit");
        assertThat(response.getBody().publishedAt()).isNotNull();
    }

    @Test
    void draftAndArchivedArticlesAnswerNotFound() {
        UUID group = createGroup("Tài khoản");
        createArticle(group, "ban-nhap", "Bản nháp", "Chưa xong", null);
        UUID archived = createArticle(group, "cach-cu", "Cách cũ", "Đã cũ", null);
        publish(archived);
        archive(archived);

        for (String slug : List.of("ban-nhap", "cach-cu", "khong-co")) {
            ResponseEntity<Problem> response = exchange(HttpMethod.GET, "/api/v1/support/kb/articles/" + slug,
                    null, null, Problem.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(response.getBody().type()).isEqualTo(PROBLEMS.resolve("article-not-found"));
        }
    }

    @Test
    void groupsListOnlyPublishedArticlesInTheAdminsGroupOrder() {
        UUID credit = createGroup("Credit");
        UUID account = createGroup("Tài khoản");
        reorder(account, credit);
        publish(createArticle(account, "doi-mat-khau", "Đổi mật khẩu", "Vào Hồ sơ", null));
        publish(createArticle(account, "dang-xuat", "Đăng xuất", "Bấm Đăng xuất", null));
        createArticle(account, "nhap", "Bản nháp", "Chưa xong", null);
        publish(createArticle(credit, "nap-credit", "Nạp credit", "Vào Ví", null));

        List<GroupView> groups = groups();

        assertThat(groups).extracting(GroupView::name).containsExactly("Tài khoản", "Credit");
        assertThat(groups.get(0).articles()).extracting(ArticleSummary::slug)
                .containsExactlyInAnyOrder("doi-mat-khau", "dang-xuat");
        assertThat(groups.get(1).articles()).extracting(ArticleSummary::slug).containsExactly("nap-credit");
    }

    @Test
    void nothingPublishedListsNoGroups() {
        UUID group = createGroup("Credit");
        createArticle(group, "nhap", "Bản nháp", "Chưa xong", null);

        assertThat(groups()).isEmpty();
    }

    @Test
    void searchIgnoresCaseAndDiacritics() {
        UUID group = createGroup("Credit");
        publish(createArticle(group, "nap-credit", "Nạp credit", "Chọn gói trong Ví", null));
        publish(createArticle(group, "doi-mat-khau", "Đổi mật khẩu", "Vào Hồ sơ", null));

        List<SearchHit> hits = search("nap CREDIT");

        assertThat(hits).extracting(SearchHit::title).containsExactly("Nạp credit");
        assertThat(search("doi")).extracting(SearchHit::slug).containsExactly("doi-mat-khau");
        assertThat(search("xyzzy")).isEmpty();
    }

    @Test
    void aOneCharacterQueryIsRefused() {
        ResponseEntity<Problem> response = exchange(HttpMethod.GET, "/api/v1/support/kb/search?q=n", null, null,
                Problem.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void searchAnswersAtMostTenResultsTitleHitsFirst() {
        UUID group = createGroup("Credit");
        publish(createArticle(group, "body-only", "Ví", "Bài này nói về credit", null));
        for (int i = 1; i <= 11; i++) {
            publish(createArticle(group, "credit-" + i, "Credit " + i, "Nội dung " + i, null));
        }

        List<SearchHit> hits = search("credit");

        assertThat(hits).hasSize(10);
        assertThat(hits).extracting(SearchHit::slug).doesNotContain("body-only");
    }

    @Test
    void aPublishedDraftShowsInItsGroupAndByItsLink() {
        UUID group = createGroup("Tài khoản");
        UUID draft = createArticle(group, "doi-mat-khau", "Đổi mật khẩu", "Vào Hồ sơ", null);
        assertThat(groups()).isEmpty();

        ResponseEntity<AdminArticleView> published = exchange(HttpMethod.POST,
                "/api/v1/admin/support/kb/articles/" + draft + "/publish", adminToken, null, AdminArticleView.class);

        assertThat(published.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(published.getBody().status()).isEqualTo("published");
        assertThat(published.getBody().publishedAt()).isNotNull();
        assertThat(groups()).singleElement().satisfies(g ->
                assertThat(g.articles()).extracting(ArticleSummary::slug).containsExactly("doi-mat-khau"));
        assertThat(exchange(HttpMethod.GET, "/api/v1/support/kb/articles/doi-mat-khau", null, null,
                ArticleView.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void anEditChangesTheArticleAndItsSearchText() {
        UUID group = createGroup("Credit");
        UUID article = createArticle(group, "nap-credit", "Nạp credit", "Chọn gói", null);
        publish(article);

        ResponseEntity<AdminArticleView> edited = exchange(HttpMethod.PUT, "/api/v1/admin/support/kb/articles/"
                + article, adminToken, new ArticleRequest(group, "nap-tien", "Nạp tiền", "Chọn gói", null),
                AdminArticleView.class);

        assertThat(edited.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(edited.getBody().slug()).isEqualTo("nap-tien");
        assertThat(search("nap tien")).extracting(SearchHit::slug).containsExactly("nap-tien");
        assertThat(exchange(HttpMethod.GET, "/api/v1/support/kb/articles/nap-credit", null, null, Problem.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void aDuplicateSlugIsRefused() {
        UUID group = createGroup("Credit");
        createArticle(group, "nap-credit", "Nạp credit", "Chọn gói", null);

        ResponseEntity<Problem> response = exchange(HttpMethod.POST, "/api/v1/admin/support/kb/articles",
                adminToken, new ArticleRequest(group, "nap-credit", "Khác", "Khác", null), Problem.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().type()).isEqualTo(PROBLEMS.resolve("slug-taken"));
    }

    @Test
    void aTitleOverOneHundredFiftyCharactersOrAMalformedSlugIsRefused() {
        UUID group = createGroup("Credit");

        ResponseEntity<Problem> longTitle = exchange(HttpMethod.POST, "/api/v1/admin/support/kb/articles",
                adminToken, new ArticleRequest(group, "dai", "x".repeat(151), "Nội dung", null), Problem.class);
        ResponseEntity<Problem> badSlug = exchange(HttpMethod.POST, "/api/v1/admin/support/kb/articles",
                adminToken, new ArticleRequest(group, "Nap Credit", "Nạp", "Nội dung", null), Problem.class);

        assertThat(longTitle.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(badSlug.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void anAdminCreatesRenamesReordersAndDeletesGroups() {
        UUID first = createGroup("Tài khoản");
        UUID second = createGroup("Credit");

        exchange(HttpMethod.PUT, "/api/v1/admin/support/kb/groups/" + first, adminToken,
                new GroupRequest("Tài khoản của bạn"), AdminGroupView.class);
        reorder(second, first);
        ResponseEntity<Void> deleted = exchange(HttpMethod.DELETE, "/api/v1/admin/support/kb/groups/" + second,
                adminToken, null, Void.class);

        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(adminGroups()).extracting(AdminGroupView::name).containsExactly("Tài khoản của bạn");
    }

    @Test
    void deletingAGroupThatHoldsAnArchivedArticleIsRefusedWithTheCount() {
        UUID group = createGroup("Credit");
        UUID article = createArticle(group, "cu", "Cũ", "Cũ", null);
        publish(article);
        archive(article);

        ResponseEntity<Problem> response = exchange(HttpMethod.DELETE, "/api/v1/admin/support/kb/groups/" + group,
                adminToken, null, Problem.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().type()).isEqualTo(PROBLEMS.resolve("group-not-empty"));
        assertThat(response.getBody().articleCount()).isEqualTo(1);
        assertThat(adminGroups()).extracting(AdminGroupView::id).containsExactly(group);
    }

    @Test
    void aFourthStartArticleIsRefused() {
        UUID group = createGroup("Bắt đầu");
        for (int position = 1; position <= 3; position++) {
            createArticle(group, "bat-dau-" + position, "Bắt đầu " + position, "Nội dung", position);
        }
        UUID fourth = createArticle(group, "bat-dau-4", "Bắt đầu 4", "Nội dung", null);

        ResponseEntity<Problem> response = exchange(HttpMethod.PUT, "/api/v1/admin/support/kb/articles/" + fourth,
                adminToken, new ArticleRequest(group, "bat-dau-4", "Bắt đầu 4", "Nội dung", 2), Problem.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().type()).isEqualTo(PROBLEMS.resolve("featured-full"));
    }

    @Test
    void archivingAStartArticleFreesItsPositionAndTheListCountsTheRest() {
        UUID group = createGroup("Bắt đầu");
        UUID first = createArticle(group, "nodi-lam-duoc-gi", "Nodi làm được gì?", "Nội dung", 1);
        UUID second = createArticle(group, "bat-dau-tu-dau", "Bắt đầu từ đâu?", "Nội dung", 2);
        UUID third = createArticle(group, "khong-dang-nhap-duoc", "Không đăng nhập được?", "Nội dung", 3);
        publish(third);
        publish(first);
        publish(second);

        AdminArticleView archived = archive(second);

        assertThat(archived.featuredPosition()).isNull();
        assertThat(featured()).extracting(FeaturedView::slug)
                .containsExactly("nodi-lam-duoc-gi", "khong-dang-nhap-duoc");
        ResponseEntity<AdminArticleList> list = exchange(HttpMethod.GET, "/api/v1/admin/support/kb/articles",
                adminToken, null, AdminArticleList.class);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(list.getBody().publishedFeaturedCount()).isEqualTo(2);
        assertThat(list.getBody().items()).hasSize(3);
        assertThat(exchange(HttpMethod.PUT, "/api/v1/admin/support/kb/articles/" + first, adminToken,
                new ArticleRequest(group, "nodi-lam-duoc-gi", "Nodi làm được gì?", "Nội dung", 2),
                AdminArticleView.class).getBody().featuredPosition()).isEqualTo(2);
    }

    @Test
    void theFeaturedListLeavesOutDrafts() {
        UUID group = createGroup("Bắt đầu");
        createArticle(group, "nhap", "Nháp", "Nội dung", 1);
        publish(createArticle(group, "da-dang", "Đã đăng", "Nội dung", 2));

        assertThat(featured()).extracting(FeaturedView::slug, FeaturedView::position)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("da-dang", 2));
    }

    @Test
    void theSettingsCarryTheConfiguredContact() {
        ResponseEntity<SettingsView> response = exchange(HttpMethod.GET, "/api/v1/support/settings", null, null,
                SettingsView.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().contactEmail()).isEqualTo("hotro@nodi.vn");
        assertThat(response.getBody().assistantAvailable()).isFalse();
    }

    @Test
    void assistantRoutesAnswer503WhileTheAssistantIsSwitchedOff() {
        ResponseEntity<Problem> response = exchange(HttpMethod.POST,
                "/api/v1/support/assistant/guest/messages", null, new GroupRequest("x"), Problem.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().type()).isEqualTo(PROBLEMS.resolve("assistant-disabled"));
    }

    @Test
    void aMemberCallingAnAdminSupportRouteIsForbidden() {
        UserView owner = userService.createInitialAdmin(uniqueEmail("kb-owner"), "password123", "Owner");
        UserView member = userService.createUser(owner.id(), uniqueEmail("kb-member"), "password123", "Member",
                "MEMBER");
        String token = login(member.email());

        ResponseEntity<Problem> response = exchange(HttpMethod.GET, "/api/v1/admin/support/kb/articles", token,
                null, Problem.class);
        ResponseEntity<Problem> create = exchange(HttpMethod.POST, "/api/v1/admin/support/kb/groups", token,
                new GroupRequest("Lạ"), Problem.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(create.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange(HttpMethod.GET, "/api/v1/support/kb/groups", token, null, GroupView[].class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Nested
    @TestPropertySource(properties = "flowcortex.support.contact-email=")
    class WithoutAContact {

        @Autowired
        private TestRestTemplate nestedRestTemplate;

        @Test
        void theContactIsNull() {
            ResponseEntity<SettingsView> response =
                    nestedRestTemplate.getForEntity("/api/v1/support/settings", SettingsView.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().contactEmail()).isNull();
        }
    }

    private UUID createGroup(String name) {
        ResponseEntity<AdminGroupView> response = exchange(HttpMethod.POST, "/api/v1/admin/support/kb/groups",
                adminToken, new GroupRequest(name), AdminGroupView.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().id();
    }

    private void reorder(UUID... ids) {
        ResponseEntity<AdminGroupView[]> response = exchange(HttpMethod.PUT, "/api/v1/admin/support/kb/groups/order",
                adminToken, new GroupOrderRequest(List.of(ids)), AdminGroupView[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private UUID createArticle(UUID group, String slug, String title, String body, Integer featuredPosition) {
        ResponseEntity<AdminArticleView> response = exchange(HttpMethod.POST, "/api/v1/admin/support/kb/articles",
                adminToken, new ArticleRequest(group, slug, title, body, featuredPosition), AdminArticleView.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().status()).isEqualTo("draft");
        return response.getBody().id();
    }

    private void publish(UUID article) {
        assertThat(exchange(HttpMethod.POST, "/api/v1/admin/support/kb/articles/" + article + "/publish", adminToken,
                null, AdminArticleView.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private AdminArticleView archive(UUID article) {
        ResponseEntity<AdminArticleView> response = exchange(HttpMethod.POST,
                "/api/v1/admin/support/kb/articles/" + article + "/archive", adminToken, null, AdminArticleView.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().status()).isEqualTo("archived");
        return response.getBody();
    }

    private List<GroupView> groups() {
        ResponseEntity<List<GroupView>> response = restTemplate.exchange("/api/v1/support/kb/groups", HttpMethod.GET,
                null, new ParameterizedTypeReference<>() { });
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private List<FeaturedView> featured() {
        ResponseEntity<List<FeaturedView>> response = restTemplate.exchange("/api/v1/support/kb/featured",
                HttpMethod.GET, null, new ParameterizedTypeReference<>() { });
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private List<SearchHit> search(String query) {
        ResponseEntity<List<SearchHit>> response = restTemplate.exchange("/api/v1/support/kb/search?q={q}",
                HttpMethod.GET, null, new ParameterizedTypeReference<>() { }, query);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private List<AdminGroupView> adminGroups() {
        ResponseEntity<List<AdminGroupView>> response = restTemplate.exchange("/api/v1/admin/support/kb/groups",
                HttpMethod.GET, new HttpEntity<>(bearer(adminToken)), new ParameterizedTypeReference<>() { });
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private <T> ResponseEntity<T> exchange(HttpMethod method, String path, String token, Object body,
            Class<T> responseType) {
        return restTemplate.exchange(path, method, new HttpEntity<>(body, bearer(token)), responseType);
    }

    private static HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return headers;
    }

    private String login(String email) {
        TokenResponse token = restTemplate.postForEntity("/api/v1/auth/login",
                new LoginRequest(email, "password123"), TokenResponse.class).getBody();
        assertThat(token).isNotNull();
        return token.accessToken();
    }

    private static String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@example.com";
    }
}
