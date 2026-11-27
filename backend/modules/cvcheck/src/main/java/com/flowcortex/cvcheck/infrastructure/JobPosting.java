package com.flowcortex.cvcheck.infrastructure;

import java.time.Instant;
import java.util.List;

public record JobPosting(
        String source,
        String externalId,
        String title,
        String company,
        String location,
        String salary,
        String workMode,
        List<String> tags,
        String description,
        String applyUrl,
        Instant publishedAt) {
}
