package com.flowcortex.cvcheck.web;

import com.flowcortex.cvcheck.application.CvCheckService;
import com.flowcortex.cvcheck.application.CvCheckService.Outcome;
import com.flowcortex.cvcheck.application.CvJobRecommendationService;
import com.flowcortex.cvcheck.application.PdfTextExtractor;
import io.swagger.v3.oas.annotations.Operation;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/cv-check")
class CvCheckController {

    private final CvCheckService service;
    private final CvJobRecommendationService jobService;

    CvCheckController(CvCheckService service, CvJobRecommendationService jobService) {
        this.service = service;
        this.jobService = jobService;
    }

    record CheckRequest(String cvText, String jobDescription) {
    }

    record RecommendJobsRequest(String primaryRole, String seniority, List<String> topSkills, String location) {
    }

    @Operation(operationId = "checkCv")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    JsonNode check(@RequestBody CheckRequest request) {
        String cv = clean(request.cvText(), CvCheckService.MAX_CV_CHARS, "cvText", true);
        String jd = clean(request.jobDescription(), CvCheckService.MAX_JD_CHARS, "jobDescription", false);
        return evaluate(cv, jd);
    }

    @Operation(operationId = "checkCvMultipart")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    JsonNode checkMultipart(
            @RequestParam(value = "cvFile", required = false) MultipartFile cvFile,
            @RequestParam(value = "cvText", required = false) String cvText,
            @RequestParam(value = "jobDescription", required = false) String jobDescription) {
        String cv;
        if (cvFile != null && !cvFile.isEmpty()) {
            try {
                cv = PdfTextExtractor.extract(cvFile.getBytes());
            } catch (IOException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "cv-file-invalid");
            }
            if (cv.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "cv-pdf-empty-text");
            }
        } else if (cvText != null && !cvText.isBlank()) {
            cv = cvText;
        } else {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "cvFile or cvText must be provided");
        }

        String cleanedCv = clean(cv, CvCheckService.MAX_CV_CHARS, "cvText", true);
        String cleanedJd = clean(jobDescription, CvCheckService.MAX_JD_CHARS, "jobDescription", false);
        return evaluate(cleanedCv, cleanedJd);
    }

    @Operation(operationId = "recommendJobsForCv")
    @PostMapping(value = "/jobs", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    JsonNode recommendJobs(@RequestBody RecommendJobsRequest request) {
        String role = clean(request.primaryRole(), 200, "primaryRole", true);
        String seniority = clean(request.seniority(), 60, "seniority", false);
        String location = clean(request.location(), 120, "location", false);
        List<String> skills = request.topSkills() == null ? List.of() : request.topSkills().stream()
                .filter(s -> s != null && !s.isBlank())
                .map(s -> clean(s, 80, "topSkills", true))
                .limit(10)
                .toList();
        return switch (jobService.recommend(role, seniority, skills, location)) {
            case CvJobRecommendationService.Outcome.Found ok -> ok.payload();
            case CvJobRecommendationService.Outcome.Refused refused -> throw mapRefusal(refused.reason());
        };
    }

    private JsonNode evaluate(String cv, String jd) {
        return switch (service.check(cv, jd)) {
            case Outcome.Report ok -> ok.report();
            case Outcome.Refused refused -> throw mapRefusal(refused.reason());
        };
    }

    private static ResponseStatusException mapRefusal(CvCheckService.Refusal reason) {
        return switch (reason) {
            case BLOCKED -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "cv-check-blocked");
            case BUSY -> new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "cv-check-busy");
            case UNAVAILABLE -> new ResponseStatusException(HttpStatus.BAD_GATEWAY, "cv-check-unavailable");
        };
    }

    private static String clean(String value, int max, String field, boolean required) {
        if (value == null || value.isBlank()) {
            if (required) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " must not be blank");
            }
            return null;
        }
        if (value.length() > max) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " must be at most " + max + " characters");
        }
        return value.strip();
    }
}
