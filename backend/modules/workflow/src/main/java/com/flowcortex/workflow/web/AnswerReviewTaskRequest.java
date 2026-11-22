package com.flowcortex.workflow.web;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;

/** Question id to the reviewer's answer: a text, or a list of texts for a multiple-choice question. */
public record AnswerReviewTaskRequest(
    @NotNull @Size(max = 5) Map<String, Object> answers
) {}
