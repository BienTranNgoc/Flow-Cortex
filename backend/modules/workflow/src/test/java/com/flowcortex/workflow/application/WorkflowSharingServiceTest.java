package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowcortex.shared.web.ConflictException;
import com.flowcortex.shared.web.LocalizedStatusException;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.shared.web.UnprocessableEntityException;
import com.flowcortex.user.api.UserDirectory;
import com.flowcortex.workflow.api.AccessLogEntryView;
import com.flowcortex.workflow.api.LinkGrantView;
import com.flowcortex.workflow.api.ShareSettingsView;
import com.flowcortex.workflow.api.SharePage;
import com.flowcortex.workflow.api.SharedWorkflowView;
import com.flowcortex.workflow.api.WorkflowVisibility;
import com.flowcortex.workflow.domain.VisibilityChangeTrigger;
import com.flowcortex.workflow.domain.Workflow;
import com.flowcortex.workflow.domain.WorkflowLinkGrant;
import com.flowcortex.workflow.domain.WorkflowStep;
import com.flowcortex.workflow.domain.WorkflowVisibilityAuditEntry;
import com.flowcortex.workflow.infrastructure.WorkflowAccessLogRepository;
import com.flowcortex.workflow.infrastructure.WorkflowLinkGrantRepository;
import com.flowcortex.workflow.infrastructure.WorkflowRepository;
import com.flowcortex.workflow.infrastructure.WorkflowStepRepository;
import com.flowcortex.workflow.infrastructure.WorkflowVisibilityAuditRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** {@link WorkflowSharingService}: the key lifecycle, key admission and the owner's lists. */
class WorkflowSharingServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-10T10:07:30Z");
    private static final Instant WINDOW = Instant.parse("2026-11-10T10:00:00Z");
    private static final UUID ANA = UUID.randomUUID();
    private static final UUID BEN = UUID.randomUUID();
    private static final UUID CLEO = UUID.randomUUID();
    private static final String SECRET = "workflow-share-hash-secret-for-unit-tests-0123456789";
    private static final ShareOrigin ORIGIN = ShareOrigin.of("203.0.113.7", "Mozilla/5.0");

    private final WorkflowServiceImpl arbiter = mock(WorkflowServiceImpl.class);
    private final WorkflowRepository workflows = mock(WorkflowRepository.class);
    private final WorkflowStepRepository steps = mock(WorkflowStepRepository.class);
    private final WorkflowLinkGrantRepository grants = mock(WorkflowLinkGrantRepository.class);
    private final WorkflowAccessLogRepository accessLogs = mock(WorkflowAccessLogRepository.class);
    private final WorkflowVisibilityAuditRepository audits = mock(WorkflowVisibilityAuditRepository.class);
    private final UserDirectory users = mock(UserDirectory.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final WorkflowAccessLog accessLog = new WorkflowAccessLog(accessLogs, new AccessorKeys(SECRET),
            mock(PlatformTransactionManager.class), clock);
    private final WorkflowSharingService service = new WorkflowSharingService(arbiter, workflows, steps, grants,
            accessLogs, audits, accessLog, users, clock, mock(PlatformTransactionManager.class));

    private Workflow locked(Workflow workflow) {
        when(arbiter.lockForWrite(any(), eq(workflow.getId()))).thenReturn(workflow);
        return workflow;
    }

    private Workflow readable(Workflow workflow) {
        when(arbiter.loadForOwnerRead(any(), eq(workflow.getId()))).thenReturn(workflow);
        return workflow;
    }

    private static Workflow privateWorkflow() {
        return new Workflow(UUID.randomUUID(), ANA, "W", false);
    }

    private static Workflow publicWorkflow() {
        return new Workflow(UUID.randomUUID(), ANA, "W", true);
    }

    private static Workflow unlisted(String key, Instant expiresAt) {
        Workflow workflow = privateWorkflow();
        workflow.makeUnlisted(key, NOW.minus(Duration.ofHours(1)), expiresAt);
        return workflow;
    }

    private WorkflowVisibilityAuditEntry savedAudit() {
        ArgumentCaptor<WorkflowVisibilityAuditEntry> captor = ArgumentCaptor.forClass(WorkflowVisibilityAuditEntry.class);
        verify(audits).save(captor.capture());
        return captor.getValue();
    }

    private static void assertDetail(Throwable thrown, HttpStatus status, String code) {
        assertThat(thrown).isInstanceOfSatisfying(LocalizedStatusException.class, e -> {
            assertThat(e.getStatusCode()).isEqualTo(status);
            assertThat(e.getDetailMessageCode()).isEqualTo(code);
        });
    }

    // --- visibility and the key lifecycle ---

    @Test
    void firstUnlistedMintsATwentyTwoCharacterKey() {
        Workflow workflow = locked(privateWorkflow());

        ShareSettingsView settings = service.setVisibility(Caller.member(ANA), workflow.getId(),
                WorkflowVisibility.UNLISTED, null, ORIGIN);

        assertThat(settings.visibility()).isEqualTo(WorkflowVisibility.UNLISTED);
        assertThat(settings.shareKey()).matches("[A-Za-z0-9_-]{22}");
        assertThat(settings.keyGeneratedAt()).isEqualTo(NOW);
        assertThat(settings.keyExpiresAt()).isNull();
        verify(workflows).save(workflow);
        assertThat(workflow.getUnlistedKey()).isEqualTo(settings.shareKey());
    }

    @Test
    void reconfirmingUnlistedKeepsAnUnexpiredKeyAndItsExpiry() {
        Instant tomorrow = NOW.plus(Duration.ofDays(1));
        Workflow workflow = locked(unlisted("K", tomorrow));

        ShareSettingsView settings = service.setVisibility(Caller.member(ANA), workflow.getId(),
                WorkflowVisibility.UNLISTED, null, ORIGIN);

        assertThat(settings.shareKey()).isEqualTo("K");
        assertThat(settings.keyExpiresAt()).isEqualTo(tomorrow);
        verify(grants, never()).deleteByWorkflowId(any());
    }

    @Test
    void reconfirmingWithANewExpiryReplacesOnlyTheExpiry() {
        Workflow workflow = locked(unlisted("K", NOW.plus(Duration.ofDays(1))));
        Instant later = NOW.plus(Duration.ofDays(7));

        ShareSettingsView settings = service.setVisibility(Caller.member(ANA), workflow.getId(),
                WorkflowVisibility.UNLISTED, later.toString(), ORIGIN);

        assertThat(settings.shareKey()).isEqualTo("K");
        assertThat(settings.keyExpiresAt()).isEqualTo(later);
        assertThat(settings.keyGeneratedAt()).isEqualTo(NOW.minus(Duration.ofHours(1)));
    }

    @Test
    void reconfirmingAfterExpiryMintsANewKey() {
        Workflow workflow = locked(unlisted("K", NOW.minus(Duration.ofDays(1))));

        ShareSettingsView settings = service.setVisibility(Caller.member(ANA), workflow.getId(),
                WorkflowVisibility.UNLISTED, null, ORIGIN);

        assertThat(settings.shareKey()).isNotEqualTo("K").matches("[A-Za-z0-9_-]{22}");
        assertThat(settings.keyGeneratedAt()).isEqualTo(NOW);
        assertThat(settings.keyExpiresAt()).isNull();
    }

    @Test
    void leavingUnlistedClearsTheKeyAndDeletesEveryGrant() {
        Workflow workflow = locked(unlisted("K", null));

        ShareSettingsView settings = service.setVisibility(Caller.member(ANA), workflow.getId(),
                WorkflowVisibility.PRIVATE, null, ORIGIN);

        assertThat(settings.visibility()).isEqualTo(WorkflowVisibility.PRIVATE);
        assertThat(settings.shareKey()).isNull();
        assertThat(workflow.getUnlistedKey()).isNull();
        verify(grants).deleteByWorkflowId(workflow.getId());
    }

    @Test
    void aPastExpiryOrAnExpiryWithoutUnlistedIsRefusedNamingExpiresAt() {
        Workflow target = locked(privateWorkflow());
        String future = NOW.plus(Duration.ofDays(1)).toString();
        List<Object[]> cases = List.of(
                new Object[] {WorkflowVisibility.UNLISTED, NOW.minus(Duration.ofDays(1)).toString()},
                new Object[] {WorkflowVisibility.UNLISTED, NOW.toString()},
                new Object[] {WorkflowVisibility.PUBLIC, future},
                new Object[] {WorkflowVisibility.PRIVATE, future});

        for (Object[] c : cases) {
            assertThatThrownBy(() -> service.setVisibility(Caller.member(ANA), target.getId(),
                    (WorkflowVisibility) c[0], (String) c[1], ORIGIN))
                    .isInstanceOfSatisfying(UnprocessableEntityException.class, e -> {
                        assertThat(e.getErrors()).containsOnlyKeys("expiresAt");
                        assertThat(e.getErrors().get("expiresAt").code()).isEqualTo("workflow.share.expiresAtInvalid");
                    });
        }

        assertThat(target.getVisibility()).isEqualTo(WorkflowVisibility.PRIVATE);
        verify(workflows, never()).save(any());
        verify(audits, never()).save(any());
        verify(grants, never()).deleteByWorkflowId(any());
    }

    @Test
    void anExpiryThatIsNotAnInstantIsRefusedNamingExpiresAt() {
        Workflow target = locked(privateWorkflow());

        for (String text : List.of("tomorrow", "", "2030-01-01")) {
            assertThatThrownBy(() -> service.setVisibility(Caller.member(ANA), target.getId(),
                    WorkflowVisibility.UNLISTED, text, ORIGIN))
                    .isInstanceOfSatisfying(UnprocessableEntityException.class, e -> {
                        assertThat(e.getErrors()).containsOnlyKeys("expiresAt");
                        assertThat(e.getErrors().get("expiresAt").code()).isEqualTo("workflow.share.expiresAtInvalid");
                    });
        }

        assertThat(target.getVisibility()).isEqualTo(WorkflowVisibility.PRIVATE);
        verify(workflows, never()).save(any());
    }

    @Test
    void regenerateOnAnExpiredKeyIsRefusedAndChangesNothing() {
        Workflow workflow = locked(unlisted("K", NOW.minus(Duration.ofDays(1))));

        assertThatThrownBy(() -> service.regenerateKey(Caller.member(ANA), workflow.getId(), ORIGIN))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.getDetail().code()).isEqualTo("workflow.share.regenerateExpired"));

        assertThat(workflow.getUnlistedKey()).isEqualTo("K");
        verify(workflows, never()).save(any());
        verify(audits, never()).save(any());
    }

    @Test
    void regenerateOnANonUnlistedWorkflowIsAConflict() {
        Workflow privateOne = locked(privateWorkflow());
        Workflow publicOne = locked(publicWorkflow());

        for (Workflow workflow : List.of(privateOne, publicOne)) {
            assertThatThrownBy(() -> service.regenerateKey(Caller.member(ANA), workflow.getId(), ORIGIN))
                    .isInstanceOfSatisfying(ConflictException.class,
                            e -> assertThat(e.getDetail().code()).isEqualTo("workflow.share.notUnlisted"));
        }

        verify(workflows, never()).save(any());
        verify(audits, never()).save(any());
    }

    @Test
    void regenerateKeepsExpiryAndGrants() {
        Instant expiry = NOW.plus(Duration.ofDays(2));
        Workflow workflow = locked(unlisted("K", expiry));

        ShareSettingsView settings = service.regenerateKey(Caller.member(ANA), workflow.getId(), ORIGIN);

        assertThat(settings.shareKey()).isNotEqualTo("K").matches("[A-Za-z0-9_-]{22}");
        assertThat(settings.keyGeneratedAt()).isEqualTo(NOW);
        assertThat(settings.keyExpiresAt()).isEqualTo(expiry);
        verify(grants, never()).deleteByWorkflowId(any());
        WorkflowVisibilityAuditEntry audit = savedAudit();
        assertThat(audit.getTriggeredBy()).isEqualTo(VisibilityChangeTrigger.KEY_REGENERATED);
        assertThat(audit.getFromVisibility()).isEqualTo(WorkflowVisibility.UNLISTED);
        assertThat(audit.getToVisibility()).isEqualTo(WorkflowVisibility.UNLISTED);
    }

    @Test
    void anAuditEntryIsWrittenOnlyWhenTheTierChangesOrTheKeyRotates() {
        Workflow workflow = locked(privateWorkflow());

        service.setVisibility(Caller.member(ANA), workflow.getId(), WorkflowVisibility.PUBLIC, null, ORIGIN);
        WorkflowVisibilityAuditEntry byOwner = savedAudit();
        assertThat(byOwner.getTriggeredBy()).isEqualTo(VisibilityChangeTrigger.OWNER);
        assertThat(byOwner.getChangedBy()).isEqualTo(ANA);
        assertThat(byOwner.getFromVisibility()).isEqualTo(WorkflowVisibility.PRIVATE);
        assertThat(byOwner.getToVisibility()).isEqualTo(WorkflowVisibility.PUBLIC);
        assertThat(byOwner.getNetwork()).isEqualTo("203.0.x.x");
        assertThat(byOwner.getUserAgent()).isEqualTo("Mozilla/5.0");
        assertThat(byOwner.getCreatedAt()).isEqualTo(NOW);

        service.setVisibility(Caller.member(ANA), workflow.getId(), WorkflowVisibility.PUBLIC, null, ORIGIN);
        verify(audits).save(any());

        service.setVisibility(Caller.admin(CLEO), workflow.getId(), WorkflowVisibility.PRIVATE, null, ORIGIN);
        ArgumentCaptor<WorkflowVisibilityAuditEntry> all = ArgumentCaptor.forClass(WorkflowVisibilityAuditEntry.class);
        verify(audits, org.mockito.Mockito.times(2)).save(all.capture());
        WorkflowVisibilityAuditEntry byAdmin = all.getAllValues().get(1);
        assertThat(byAdmin.getTriggeredBy()).isEqualTo(VisibilityChangeTrigger.ADMIN);
        assertThat(byAdmin.getChangedBy()).isEqualTo(CLEO);
    }

    // --- key admission ---

    private Workflow published(Workflow workflow) {
        when(workflows.findByUnlistedKey(workflow.getUnlistedKey())).thenReturn(Optional.of(workflow));
        return workflow;
    }

    @Test
    void anUnknownKeyIsNotFound() {
        when(workflows.findByUnlistedKey("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.sharedView(null, "nope", ORIGIN)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.admitByKey(BEN, UUID.randomUUID(), "nope", ORIGIN))
                .isInstanceOf(NotFoundException.class);

        verify(grants, never()).admit(any(), any(), any(), any());
    }

    @Test
    void aKeyOfAnotherWorkflowIsNotFound() {
        Workflow workflow = published(unlisted("K", null));

        assertThatThrownBy(() -> service.admitByKey(BEN, UUID.randomUUID(), "K", ORIGIN))
                .isInstanceOf(NotFoundException.class);

        assertThat(workflow.getUnlistedKey()).isEqualTo("K");
        verify(grants, never()).admit(any(), any(), any(), any());
        verify(accessLogs, never()).insertOnce(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void anExpiredKeyIsGone() {
        Workflow workflow = published(unlisted("K", NOW.minus(Duration.ofSeconds(1))));

        assertThatThrownBy(() -> service.sharedView(null, "K", ORIGIN))
                .satisfies(e -> assertDetail(e, HttpStatus.GONE, "workflow.share.keyExpired"));
        assertThatThrownBy(() -> service.admitByKey(BEN, workflow.getId(), "K", ORIGIN))
                .satisfies(e -> assertDetail(e, HttpStatus.GONE, "workflow.share.keyExpired"));

        verify(grants, never()).admit(any(), any(), any(), any());
        verify(accessLogs, never()).insertOnce(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void aKeyOfADraftOrDeletedWorkflowIsNotFound() {
        Workflow draft = Workflow.draft(UUID.randomUUID(), ANA, "D");
        draft.makeUnlisted("DRAFT", NOW, null);
        Workflow deleted = unlisted("DELETED", null);
        deleted.markDeleted();
        Workflow expiredDraft = Workflow.draft(UUID.randomUUID(), ANA, "E");
        expiredDraft.makeUnlisted("EXPIRED_DRAFT", NOW, NOW.minus(Duration.ofDays(1)));

        for (Workflow workflow : List.of(draft, deleted, expiredDraft)) {
            published(workflow);
            assertThatThrownBy(() -> service.sharedView(null, workflow.getUnlistedKey(), ORIGIN))
                    .isInstanceOf(NotFoundException.class);
            assertThatThrownBy(() -> service.admitByKey(BEN, workflow.getId(), workflow.getUnlistedKey(), ORIGIN))
                    .isInstanceOf(NotFoundException.class);
        }

        verify(grants, never()).admit(any(), any(), any(), any());
    }

    @Test
    void theOwnerPresentingTheKeyGetsNoGrant() {
        Workflow workflow = published(unlisted("K", null));

        service.admitByKey(ANA, workflow.getId(), "K", ORIGIN);

        verify(grants, never()).admit(any(), any(), any(), any());
        verify(accessLogs, never()).insertOnce(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void aSignedInRecipientIsAdmittedAndTheReadIsLogged() {
        Workflow workflow = published(unlisted("K", NOW.plus(Duration.ofDays(1))));

        service.admitByKey(BEN, workflow.getId(), "K", ORIGIN);

        verify(grants).admit(workflow.getId(), BEN, "K", NOW);
        verify(accessLogs).insertOnce(any(), eq(workflow.getId()), eq(BEN), eq("203.0.x.x"), eq("Mozilla/5.0"),
                eq("u:" + BEN), eq(WINDOW), eq(NOW));
    }

    @Test
    void anAnonymousReaderGetsTheNarrowViewAndAHashedLogEntryButNoGrant() {
        Workflow workflow = published(unlisted("K", null));
        when(users.findNamesByIds(List.of(ANA))).thenReturn(Map.of(ANA, "Ana"));
        when(steps.findByWorkflowIdOrderByStepOrderAsc(workflow.getId())).thenReturn(List.of(
                new WorkflowStep(UUID.randomUUID(), workflow.getId(), (short) 1, "Caption", "echo", null),
                new WorkflowStep(UUID.randomUUID(), workflow.getId(), (short) 2, "Publish", "echo", null)));

        SharedWorkflowView view = service.sharedView(null, "K", ORIGIN);

        assertThat(view.id()).isEqualTo(workflow.getId());
        assertThat(view.name()).isEqualTo("W");
        assertThat(view.authorName()).isEqualTo("Ana");
        assertThat(view.steps()).containsExactly(new SharedWorkflowView.Step(1, "Caption", "echo"),
                new SharedWorkflowView.Step(2, "Publish", "echo"));
        verify(grants, never()).admit(any(), any(), any(), any());
        ArgumentCaptor<String> accessor = ArgumentCaptor.forClass(String.class);
        verify(accessLogs).insertOnce(any(), eq(workflow.getId()), eq(null), eq("203.0.x.x"), eq("Mozilla/5.0"),
                accessor.capture(), eq(WINDOW), eq(NOW));
        assertThat(accessor.getValue()).matches("n:[0-9a-f]{64}").doesNotContain("203.0.113.7");
    }

    @Test
    void aSignedInReaderOfTheByKeyViewIsLoggedByUserWithoutAGrant() {
        Workflow workflow = published(unlisted("K", null));

        service.sharedView(BEN, "K", ORIGIN);

        verify(grants, never()).admit(any(), any(), any(), any());
        verify(accessLogs).insertOnce(any(), eq(workflow.getId()), eq(BEN), any(), any(), eq("u:" + BEN),
                eq(WINDOW), eq(NOW));
    }

    @Test
    void aFailingAccessLogWriteDoesNotFailTheRead() {
        Workflow workflow = published(unlisted("K", null));
        when(accessLogs.insertOnce(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("log store down"));

        assertThat(service.sharedView(null, "K", ORIGIN).id()).isEqualTo(workflow.getId());
        service.admitByKey(BEN, workflow.getId(), "K", ORIGIN);

        verify(grants).admit(workflow.getId(), BEN, "K", NOW);
    }

    /** Opens a real transaction scope around each template call, so nesting shows as an active transaction. */
    private static final class ScopeOnlyTransactionManager extends AbstractPlatformTransactionManager {
        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }

    @Test
    void theAccessLogEntryIsWrittenOutsideTheReadAndGrantTransactions() {
        WorkflowSharingService scoped = new WorkflowSharingService(arbiter, workflows, steps, grants, accessLogs,
                audits, accessLog, users, clock, new ScopeOnlyTransactionManager());
        Workflow workflow = published(unlisted("K", null));
        List<Boolean> grantInTransaction = new ArrayList<>();
        List<Boolean> logInTransaction = new ArrayList<>();
        when(grants.admit(any(), any(), any(), any())).thenAnswer(call -> {
            grantInTransaction.add(TransactionSynchronizationManager.isActualTransactionActive());
            return 1;
        });
        when(accessLogs.insertOnce(any(), any(), any(), any(), any(), any(), any(), any())).thenAnswer(call -> {
            logInTransaction.add(TransactionSynchronizationManager.isActualTransactionActive());
            return 1;
        });

        scoped.admitByKey(BEN, workflow.getId(), "K", ORIGIN);
        scoped.sharedView(null, "K", ORIGIN);

        assertThat(grantInTransaction).containsExactly(true);
        assertThat(logInTransaction).containsExactly(false, false);
    }

    // --- the owner's lists ---

    private static String cursorOf(Instant at, UUID id) {
        long micros = at.getEpochSecond() * 1_000_000L + at.getNano() / 1_000;
        return Base64.getUrlEncoder().withoutPadding().encodeToString((micros + ":" + id).getBytes());
    }

    @Test
    void aLimitOutsideOneToAHundredOrAMalformedCursorIsRefusedNamingTheParameter() {
        Workflow workflow = readable(publicWorkflow());
        Caller ana = Caller.member(ANA);

        for (int limit : new int[] {0, -1, 101}) {
            assertThatThrownBy(() -> service.accessLog(ana, workflow.getId(), limit, null)).satisfies(e -> {
                assertDetail(e, HttpStatus.BAD_REQUEST, "workflow.share.pageInvalid");
                assertThat(((LocalizedStatusException) e).getDetailMessageArguments()).containsExactly("limit");
            });
        }
        for (String cursor : new String[] {"not-base64!", Base64.getUrlEncoder().withoutPadding()
                .encodeToString("garbage".getBytes()), cursorOf(NOW, ANA).substring(3)}) {
            assertThatThrownBy(() -> service.linkGrants(ana, workflow.getId(), 10, cursor)).satisfies(e -> {
                assertDetail(e, HttpStatus.BAD_REQUEST, "workflow.share.pageInvalid");
                assertThat(((LocalizedStatusException) e).getDetailMessageArguments()).containsExactly("cursor");
            });
        }
    }

    @Test
    void linkGrantsPageNewestFirstWithNamesAndACursorThatContinuesAfterTheLastRow() {
        Workflow workflow = readable(unlisted("K", null));
        UUID dan = UUID.randomUUID();
        UUID eve = UUID.randomUUID();
        Instant same = NOW.minusSeconds(5);
        when(grants.firstPage(workflow.getId(), 3)).thenReturn(List.of(
                new WorkflowLinkGrant(workflow.getId(), BEN, NOW), new WorkflowLinkGrant(workflow.getId(), dan, same),
                new WorkflowLinkGrant(workflow.getId(), eve, same)));
        when(users.findNamesByIds(any())).thenReturn(Map.of(BEN, "Ben", dan, "Dan"));

        SharePage<LinkGrantView> first = service.linkGrants(Caller.member(ANA), workflow.getId(), 2, null);

        assertThat(first.hasMore()).isTrue();
        assertThat(first.total7d()).isNull();
        assertThat(first.data()).containsExactly(new LinkGrantView(BEN, "Ben", NOW),
                new LinkGrantView(dan, "Dan", same));
        assertThat(first.nextCursor()).isEqualTo(cursorOf(same, dan));

        when(grants.page(workflow.getId(), same, dan, 3))
                .thenReturn(List.of(new WorkflowLinkGrant(workflow.getId(), eve, same)));
        SharePage<LinkGrantView> second = service.linkGrants(Caller.member(ANA), workflow.getId(), 2,
                first.nextCursor());

        assertThat(second.hasMore()).isFalse();
        assertThat(second.nextCursor()).isNull();
        assertThat(second.data()).extracting(LinkGrantView::userId).containsExactly(eve);
        assertThat(second.data().get(0).name()).isNull();
    }

    @Test
    void theAccessLogCarriesTheSevenDayTotalAndNamesOnlySignedInReaders() {
        Workflow workflow = readable(unlisted("K", null));
        when(accessLogs.firstPage(eq(workflow.getId()), anyInt())).thenReturn(List.of());
        when(accessLogs.countByWorkflowIdAndCreatedAtGreaterThanEqual(workflow.getId(),
                NOW.minus(Duration.ofDays(7)))).thenReturn(40L);

        SharePage<AccessLogEntryView> page = service.accessLog(Caller.member(ANA), workflow.getId(), 100, null);

        assertThat(page.total7d()).isEqualTo(40L);
        assertThat(page.hasMore()).isFalse();
        assertThat(page.data()).isEmpty();
    }
}
