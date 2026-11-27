package com.flowcortex.cvcheck.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowcortex.cvcheck.application.CvJobRecommendationService.Outcome;
import com.flowcortex.cvcheck.infrastructure.CvJobPostingRepository;
import com.flowcortex.cvcheck.infrastructure.JobPosting;
import com.flowcortex.llm.api.LlmJsonResult;
import com.flowcortex.llm.api.LlmSlots;
import com.flowcortex.llm.api.LlmSpeechResult;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class CvJobRecommendationServiceTest {

    private static final Instant SYNCED_AT = Instant.now().minus(Duration.ofHours(2));

    private static final List<JobPosting> VN_POSTINGS = List.of(
            new JobPosting("VietnamWorks", "vn-1", "Java Backend Developer", "VNG", "Ho Chi Minh City, Vietnam",
                    "$1,500 - $2,500", "Hybrid", List.of("Java", "Spring Boot"), "Spring Boot microservices in HCMC",
                    "https://www.vietnamworks.com/job/vn-1", SYNCED_AT),
            new JobPosting("TopCV", "vn-2", "Software Engineer Intern", "FPT Software", "Hanoi, Vietnam",
                    "8-12 triệu VND", "Onsite", List.of("Java", "SQL"), "Thực tập sinh kỹ sư phần mềm",
                    "https://www.topcv.vn/viec-lam/vn-2", SYNCED_AT));

    private static LlmSlots slots(LlmJsonResult result, AtomicInteger calls) {
        return new LlmSlots() {
            @Override
            public LlmJsonResult generateJson(String slot, String userText, String schema) {
                calls.incrementAndGet();
                assertThat(slot).isEqualTo(CvCheckService.SLOT);
                return result;
            }

            @Override
            public LlmSpeechResult speak(String slot, String text, String voice) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Duration jsonWorstCase(String slot) {
                return Duration.ZERO;
            }

            @Override
            public Duration speechWorstCase(String slot) {
                return Duration.ZERO;
            }

            @Override
            public boolean configured() {
                return true;
            }
        };
    }

    @Test
    void reusesRoleCacheForDifferentUsersWithSameRoleWithoutExternalCalls() {
        CvJobPostingRepository repo = mock(CvJobPostingRepository.class);
        CvJobFeedSyncJob syncJob = mock(CvJobFeedSyncJob.class);
        when(syncJob.refreshWindow()).thenReturn(Duration.ofDays(7));
        when(syncJob.providerConfigured()).thenReturn(true);
        when(repo.findFreshByRoleKey(eq("java backend developer"), any(), anyInt())).thenReturn(VN_POSTINGS);
        when(repo.lastSyncedAtForRole("java backend developer")).thenReturn(Optional.of(SYNCED_AT));

        String llmAnswer = """
                {"picks":[
                  {"index":0,"matchScore":92,"matchedSkills":["Java","Spring Boot"],
                   "summary":"Phù hợp rất tốt với kỹ năng Java & Spring Boot tại TP.HCM."}
                ]}
                """;
        AtomicInteger llmCalls = new AtomicInteger();
        CvJobRecommendationService service = new CvJobRecommendationService(
                slots(new LlmJsonResult.Answered(llmAnswer, "gemini-3.5-flash-lite", 10, 20), llmCalls),
                repo, syncJob);

        Outcome first = service.recommend("Java Backend Developer", "Middle", List.of("Java", "Spring Boot"), "TP.HCM");
        Outcome second = service.recommend("Java Backend Developer", "Junior", List.of("Java"), "Hà Nội");

        verify(syncJob, never()).fetchAndStoreForRole(any(), any(), any());
        assertThat(llmCalls.get()).isEqualTo(1);

        JsonNode firstPayload = ((Outcome.Found) first).payload();
        JsonNode secondPayload = ((Outcome.Found) second).payload();
        assertThat(firstPayload.path("jobs")).hasSize(1);
        assertThat(firstPayload.path("cached").asBoolean()).isTrue();
        assertThat(secondPayload.path("cached").asBoolean()).isTrue();
        assertThat(firstPayload.path("jobs").get(0).path("company").asString()).isEqualTo("VNG");
    }

    @Test
    void fetchesOnDemandWhenRoleNotInDbAndFallsBackWhenLlmFails() {
        CvJobPostingRepository repo = mock(CvJobPostingRepository.class);
        CvJobFeedSyncJob syncJob = mock(CvJobFeedSyncJob.class);
        when(syncJob.refreshWindow()).thenReturn(Duration.ofDays(7));
        when(syncJob.providerConfigured()).thenReturn(true);
        when(repo.findFreshByRoleKey(eq("software engineer intern"), any(), anyInt())).thenReturn(List.of());
        when(repo.lastSyncedAtForRole("software engineer intern")).thenReturn(Optional.empty());
        when(syncJob.fetchAndStoreForRole("software engineer intern", "Software Engineer Intern", "Ho Chi Minh City"))
                .thenReturn(VN_POSTINGS);

        AtomicInteger llmCalls = new AtomicInteger();
        CvJobRecommendationService service = new CvJobRecommendationService(
                slots(new LlmJsonResult.Failed(LlmJsonResult.Kind.QUOTA_EXCEEDED), llmCalls),
                repo, syncJob);

        Outcome outcome = service.recommend("Software Engineer Intern", "Intern", List.of("Java"), "Ho Chi Minh City");

        verify(syncJob).fetchAndStoreForRole("software engineer intern", "Software Engineer Intern", "Ho Chi Minh City");
        JsonNode payload = ((Outcome.Found) outcome).payload();
        assertThat(payload.path("cached").asBoolean()).isFalse();
        assertThat(payload.path("jobs")).hasSize(2);
    }

    @Test
    void returnsUnconfiguredFlagAndVietnamBoardLinksWhenNoApiKey() {
        CvJobPostingRepository repo = mock(CvJobPostingRepository.class);
        CvJobFeedSyncJob syncJob = mock(CvJobFeedSyncJob.class);
        when(syncJob.refreshWindow()).thenReturn(Duration.ofDays(7));
        when(syncJob.providerConfigured()).thenReturn(false);
        when(repo.findFreshByRoleKey(any(), any(), anyInt())).thenReturn(List.of());
        when(repo.lastSyncedAtForRole(any())).thenReturn(Optional.empty());
        when(syncJob.fetchAndStoreForRole(any(), any(), any())).thenReturn(List.of());

        AtomicInteger llmCalls = new AtomicInteger();
        CvJobRecommendationService service = new CvJobRecommendationService(
                slots(new LlmJsonResult.Answered("{}", "m", 1, 1), llmCalls), repo, syncJob);

        Outcome outcome = service.recommend("Software Engineer Intern", "Intern", List.of("React"), "TP.HCM");
        JsonNode payload = ((Outcome.Found) outcome).payload();
        assertThat(payload.path("configured").asBoolean()).isFalse();
        assertThat(payload.path("jobs")).isEmpty();
        assertThat(payload.path("searchLinks")).hasSize(4);
    }
}
