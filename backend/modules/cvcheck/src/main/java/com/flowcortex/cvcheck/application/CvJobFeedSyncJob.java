package com.flowcortex.cvcheck.application;

import com.flowcortex.cvcheck.infrastructure.CvJobPostingRepository;
import com.flowcortex.cvcheck.infrastructure.JobFeedClient;
import com.flowcortex.cvcheck.infrastructure.JobPosting;
import com.flowcortex.jobs.api.ClusterJob;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class CvJobFeedSyncJob implements ClusterJob {

    private static final Logger LOG = LoggerFactory.getLogger(CvJobFeedSyncJob.class);
    private static final int MAX_WEEKLY_ROLES = 12;

    private final JobFeedClient feedClient;
    private final CvJobPostingRepository repository;
    private final Duration interval;
    private final ConcurrentHashMap<String, ReentrantLock> roleLocks = new ConcurrentHashMap<>();

    CvJobFeedSyncJob(
            JobFeedClient feedClient,
            CvJobPostingRepository repository,
            @Value("${flowcortex.cvcheck.job-sync-interval:P7D}") Duration interval) {
        this.feedClient = feedClient;
        this.repository = repository;
        this.interval = interval;
    }

    @Override
    public String name() {
        return "cvcheck.job-feed-sync";
    }

    @Override
    public Duration interval() {
        return interval;
    }

    @Override
    public Duration lease() {
        return Duration.ofMinutes(5);
    }

    public Duration refreshWindow() {
        return interval;
    }

    public boolean providerConfigured() {
        return feedClient.configured();
    }

    @Override
    public void run() {
        Instant now = Instant.now();
        int pruned = repository.deleteStale(now.minus(interval));
        if (!feedClient.configured()) {
            LOG.info("cvcheck.job_feed_sync configured=false pruned={}", pruned);
            return;
        }
        List<String> roles = repository.findDistinctRoleKeys(MAX_WEEKLY_ROLES);
        int total = 0;
        for (String roleKey : roles) {
            total += fetchAndStoreForRole(roleKey, roleKey, "Vietnam").size();
        }
        LOG.info("cvcheck.job_feed_sync roles={} refreshed={} pruned={}", roles.size(), total, pruned);
    }

    public List<JobPosting> fetchAndStoreForRole(String roleKey, String roleQuery, String location) {
        if (!feedClient.configured()) {
            return List.of();
        }
        ReentrantLock lock = roleLocks.computeIfAbsent(roleKey, k -> new ReentrantLock());
        lock.lock();
        try {
            Instant now = Instant.now();
            List<JobPosting> existing = repository.findFreshByRoleKey(roleKey, now.minus(interval), 30);
            if (!existing.isEmpty()) {
                return existing;
            }
            List<JobPosting> fetched = feedClient.searchVietnamJobs(roleQuery, location);
            if (!fetched.isEmpty()) {
                repository.upsertForRole(roleKey, fetched, now);
                LOG.info("cvcheck.job_feed_role_sync roleKey={} fetched={}", roleKey, fetched.size());
            }
            return fetched;
        } finally {
            lock.unlock();
        }
    }
}
