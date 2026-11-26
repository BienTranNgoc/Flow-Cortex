package com.flowcortex.app.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowcortex.AbstractPostgresBootTest;
import com.flowcortex.billing.profile.BillingProfile;
import com.flowcortex.billing.profile.BillingProfileNotFoundException;
import com.flowcortex.billing.profile.BillingProfiles;
import com.flowcortex.shared.web.UnprocessableEntityException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
class BillingProfilesIT extends AbstractPostgresBootTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PERSONAL = """
            {"type":"personal","legalName":"Nguyễn Văn A","personalIdNumber":"001099012345",
             "address":"12 Lý Thường Kiệt, Hà Nội"}""";
    private static final String BUSINESS = """
            {"type":"business","legalName":"Công ty ABC","taxCode":"0101243150","address":"1 Tràng Tiền"}""";

    private UUID userA;
    private UUID userB;

    @Autowired
    private BillingProfiles profiles;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void users() {
        userA = insertUser();
        userB = insertUser();
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM app_user WHERE id IN (?, ?)", userA, userB);
    }

    @Test
    void firstProfileIsTheDefaultWhateverTheBodySays() {
        BillingProfile first = profiles.create(userA, json(PERSONAL.replace("{", "{\"isDefault\":false,")));

        assertThat(first.isDefault()).isTrue();
        assertThat(profiles.findDefault(userA)).contains(first);
    }

    @Test
    void aNewDefaultMovesTheFlagAndOnlyOneRowHoldsIt() {
        BillingProfile first = profiles.create(userA, json(PERSONAL));
        BillingProfile second = profiles.create(userA, json(BUSINESS.replace("{", "{\"isDefault\":true,")));

        assertThat(second.isDefault()).isTrue();
        assertThat(profiles.find(userA, first.id()).orElseThrow().isDefault()).isFalse();
        assertThat(defaultCount(userA)).isEqualTo(1);
    }

    @Test
    void theListIsTheDefaultFirstThenOldest() {
        BillingProfile p1 = profiles.create(userA, json(PERSONAL));
        BillingProfile p2 = profiles.create(userA, json(BUSINESS));
        BillingProfile p3 = profiles.create(userA, json(BUSINESS));
        profiles.setDefault(userA, p2.id());

        assertThat(profiles.list(userA)).extracting(BillingProfile::id).containsExactly(p2.id(), p1.id(), p3.id());
    }

    @Test
    void aTaxCodeSentWithAPersonalProfileIsNotStored() {
        BillingProfile created = profiles.create(userA, json(PERSONAL.replace("{", "{\"taxCode\":\"0101243150\",")));

        assertThat(created.taxCode()).isNull();
        assertThat(created.personalIdNumber()).isEqualTo("001099012345");
    }

    @Test
    void anInvalidBodyIsRefusedWithItsFieldAndStoresNothing() {
        assertThatThrownBy(() -> profiles.create(userA, json("""
                {"type":"business","legalName":"Công ty ABC","address":"1 Tràng Tiền"}""")))
                .isInstanceOfSatisfying(UnprocessableEntityException.class,
                        e -> assertThat(e.getErrors()).containsOnlyKeys("taxCode"));

        assertThat(profiles.list(userA)).isEmpty();
    }

    @Test
    void mergeWithIsDefaultFalseKeepsTheDefault() {
        BillingProfile first = profiles.create(userA, json(PERSONAL));

        BillingProfile updated = profiles.update(userA, first.id(), json("{\"isDefault\":false,\"email\":\"a@b.vn\"}"));

        assertThat(updated.isDefault()).isTrue();
        assertThat(updated.email()).isEqualTo("a@b.vn");
        assertThat(updated.legalName()).isEqualTo("Nguyễn Văn A");
    }

    @Test
    void aPatchThatBreaksTheMergedProfileIsRefusedAndTheProfileIsUnchanged() {
        BillingProfile first = profiles.create(userA, json(PERSONAL));

        assertThatThrownBy(() -> profiles.update(userA, first.id(), json("{\"type\":\"business\"}")))
                .isInstanceOfSatisfying(UnprocessableEntityException.class,
                        e -> assertThat(e.getErrors()).containsOnlyKeys("taxCode"));

        assertThat(profiles.find(userA, first.id())).contains(first);
    }

    @Test
    void deletingTheDefaultPromotesTheOldestRemaining() {
        BillingProfile p1 = profiles.create(userA, json(PERSONAL));
        BillingProfile p2 = profiles.create(userA, json(BUSINESS));
        BillingProfile p3 = profiles.create(userA, json(BUSINESS));
        profiles.setDefault(userA, p3.id());

        profiles.delete(userA, p3.id());

        assertThat(profiles.findDefault(userA).orElseThrow().id()).isEqualTo(p1.id());
        assertThat(profiles.list(userA)).extracting(BillingProfile::id).containsExactly(p1.id(), p2.id());
    }

    @Test
    void deletingANonDefaultKeepsTheDefault() {
        BillingProfile p1 = profiles.create(userA, json(PERSONAL));
        BillingProfile p2 = profiles.create(userA, json(BUSINESS));

        profiles.delete(userA, p2.id());

        assertThat(profiles.findDefault(userA).orElseThrow().id()).isEqualTo(p1.id());
    }

    @Test
    void deletingTheLastProfileLeavesNoDefault() {
        BillingProfile only = profiles.create(userA, json(PERSONAL));

        profiles.delete(userA, only.id());

        assertThat(profiles.findDefault(userA)).isEmpty();
        assertThat(profiles.list(userA)).isEmpty();
    }

    @Test
    void anotherMembersProfileIsNotFoundAndUnchanged() {
        BillingProfile ofB = profiles.create(userB, json(PERSONAL));

        assertThat(profiles.find(userA, ofB.id())).isEmpty();
        assertThatThrownBy(() -> profiles.update(userA, ofB.id(), json("{\"email\":\"x@y.vn\"}")))
                .isInstanceOf(BillingProfileNotFoundException.class);
        assertThatThrownBy(() -> profiles.setDefault(userA, ofB.id()))
                .isInstanceOf(BillingProfileNotFoundException.class);
        assertThatThrownBy(() -> profiles.delete(userA, ofB.id()))
                .isInstanceOf(BillingProfileNotFoundException.class);

        assertThat(profiles.find(userB, ofB.id())).contains(ofB);
    }

    @Test
    void aSecondDefaultInsertedBySqlIsRefused() {
        profiles.create(userA, json(PERSONAL));

        assertThatThrownBy(() -> insertRow(userA, "business", "0101243150", null, true))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theTableRefusesATypeOutsideTheThreeAndTheForcedNulls() {
        assertThatThrownBy(() -> insertRow(userA, "company", null, null, false))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertRow(userA, "personal", "0101243150", "001099012345", false))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertRow(userA, "business", "0101243150", "001099012345", false))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertRow(userA, "personal", null, "00109901234", false))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theTaxCodeShapeIsTheRulesNotTheTables() {
        insertRow(userA, "business", "not-a-code", null, false);

        assertThat(profiles.list(userA)).extracting(BillingProfile::taxCode).containsExactly("not-a-code");
    }

    @Test
    void twoFirstProfilesAtOnceStoreBothWithOneDefault() throws Exception {
        runAtOnce(() -> profiles.create(userA, json(PERSONAL)), () -> profiles.create(userA, json(BUSINESS)));

        assertThat(profiles.list(userA)).hasSize(2);
        assertThat(defaultCount(userA)).isEqualTo(1);
    }

    @Test
    void twoDefaultsChosenAtOnceBothSucceedWithOneDefault() throws Exception {
        BillingProfile p1 = profiles.create(userA, json(PERSONAL));
        BillingProfile p2 = profiles.create(userA, json(BUSINESS));

        runAtOnce(() -> profiles.setDefault(userA, p1.id()), () -> profiles.setDefault(userA, p2.id()));

        assertThat(defaultCount(userA)).isEqualTo(1);
    }

    @Test
    void deletingTheUserRemovesTheProfiles() {
        profiles.create(userA, json(PERSONAL));
        profiles.create(userA, json(BUSINESS));

        jdbc.update("DELETE FROM app_user WHERE id = ?", userA);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM billing_profile WHERE user_id = ?", Integer.class, userA))
                .isZero();
    }

    @Test
    void theResponseNamesTheDefaultFlagIsDefault() {
        BillingProfile created = profiles.create(userA, json(PERSONAL));

        assertThat(JSON.valueToTree(created).has("isDefault")).isTrue();
    }

    @SafeVarargs
    private static void runAtOnce(Supplier<?>... calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.length);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Object>> running = new ArrayList<>();
            for (Supplier<?> call : calls) {
                running.add(pool.submit(() -> {
                    go.await();
                    return call.get();
                }));
            }
            go.countDown();
            for (Future<Object> run : running) {
                run.get();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private int defaultCount(UUID user) {
        return jdbc.queryForObject("SELECT count(*) FROM billing_profile WHERE user_id = ? AND is_default",
                Integer.class, user);
    }

    private void insertRow(UUID user, String type, String taxCode, String personalId, boolean isDefault) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO billing_profile (id, user_id, type, legal_name, tax_code, personal_id_number, address,"
                + " is_default, created_at, updated_at) VALUES (?, ?, ?, 'Name', ?, ?, 'Address', ?, ?, ?)",
                UUID.randomUUID(), user, type, taxCode, personalId, isDefault, now, now);
    }

    private UUID insertUser() {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO app_user (id, owner_id, email, password_hash, name, role, status, created_at,"
                + " updated_at) VALUES (?, ?, ?, 'x', 'Profile owner', 'MEMBER', 'ACTIVE', ?, ?)",
                id, id, id + "@billing-profiles.test", now, now);
        return id;
    }

    private static JsonNode json(String body) {
        return JSON.readTree(body);
    }
}
