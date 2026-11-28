package com.flowcortex.cvcheck.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class JobFeedClientTest {

    private final JobFeedClient client = new JobFeedClient();

    @Test
    void normalizesVietnamLocations() {
        assertThat(JobFeedClient.normalizeVietnamLocation("TP. Hồ Chí Minh")).isEqualTo("Ho Chi Minh City, Vietnam");
        assertThat(JobFeedClient.normalizeVietnamLocation("Hà Nội")).isEqualTo("Hanoi, Vietnam");
        assertThat(JobFeedClient.normalizeVietnamLocation("Đà Nẵng")).isEqualTo("Da Nang, Vietnam");
        assertThat(JobFeedClient.normalizeVietnamLocation("")).isEqualTo("Vietnam");
    }

    @Test
    void parsesSerpApiVietnamJobs() {
        String body = """
                {
                  "jobs_results": [
                    {
                      "job_id": "serp-vn-1",
                      "title": "Software Engineer Intern",
                      "company_name": "VNG Corporation",
                      "location": "Ho Chi Minh City, Vietnam",
                      "via": "via VietnamWorks",
                      "schedule_type": "Internship",
                      "detected_extensions": {
                        "schedule_type": "Internship",
                        "salary": "8-12 triệu VND",
                        "work_from_home": false
                      },
                      "description": "Tham gia phát triển hệ thống backend Java tại TP.HCM.",
                      "apply_options": [
                        {"title": "Apply on VietnamWorks", "link": "https://www.vietnamworks.com/job/123"}
                      ]
                    }
                  ]
                }
                """;

        List<JobPosting> jobs = client.parseSerpApi(body);
        assertThat(jobs).hasSize(1);
        JobPosting job = jobs.getFirst();
        assertThat(job.source()).isEqualTo("VietnamWorks");
        assertThat(job.location()).isEqualTo("Ho Chi Minh City, Vietnam");
        assertThat(job.salary()).isEqualTo("8-12 triệu VND");
        assertThat(job.applyUrl()).isEqualTo("https://www.vietnamworks.com/job/123");
    }

    @Test
    void parsesJSearchVietnamJobsAndSkipsUnsafeUrls() {
        String body = """
                {
                  "data": [
                    {
                      "job_id": "js-vn-1",
                      "job_title": "Java Backend Developer",
                      "employer_name": "FPT Software",
                      "job_publisher": "TopCV",
                      "job_employment_type": "FULLTIME",
                      "job_apply_link": "https://www.topcv.vn/viec-lam/java-123",
                      "job_description": "<p>Develop Spring Boot services in Hanoi.</p>",
                      "job_is_remote": false,
                      "job_location": "Hanoi, VN",
                      "job_min_salary": 1500,
                      "job_max_salary": 2500,
                      "job_salary_currency": "USD",
                      "job_salary_period": "MONTH",
                      "job_posted_at_timestamp": 1764230400
                    },
                    {
                      "job_id": "js-bad",
                      "job_title": "Unsafe Job",
                      "employer_name": "BadCo",
                      "job_apply_link": "javascript:alert(1)"
                    }
                  ]
                }
                """;

        List<JobPosting> jobs = client.parseJSearch(body);
        assertThat(jobs).hasSize(1);
        JobPosting job = jobs.getFirst();
        assertThat(job.source()).isEqualTo("TopCV");
        assertThat(job.location()).isEqualTo("Hanoi, VN");
        assertThat(job.salary()).isEqualTo("1500 - 2500 USD/MONTH");
        assertThat(job.description()).isEqualTo("Develop Spring Boot services in Hanoi.");
        assertThat(job.applyUrl()).isEqualTo("https://www.topcv.vn/viec-lam/java-123");
    }
}
