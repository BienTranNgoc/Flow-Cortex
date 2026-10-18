package com.flowcortex.shared.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Authored (and run red, with {@link GlobalExceptionHandler} temporarily
 * removed) before the advice was wired.
 */
@WebMvcTest(ThrowawayTestController.class)
class GlobalExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void notFoundReturnsProblemDetail() throws Exception {
        mockMvc.perform(get("/test/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.valueOf("application/problem+json")))
                .andExpect(jsonPath("$.type").exists())
                .andExpect(jsonPath("$.title").exists())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("thing not found"));
    }

    @Test
    void conflictReturnsProblemDetail() throws Exception {
        mockMvc.perform(get("/test/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.detail").value("thing conflicts"));
    }

    @Test
    void unprocessableReturns422WithItsCodeAndFieldErrors() throws Exception {
        mockMvc.perform(get("/test/unprocessable"))
                .andExpect(status().is(422))
                .andExpect(content().contentTypeCompatibleWith(MediaType.valueOf("application/problem+json")))
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.detail").value("thing is incomplete"))
                .andExpect(jsonPath("$.code").value("THING_INCOMPLETE"))
                .andExpect(jsonPath("$.errors['steps[1].config']").value("is missing"));
    }

    @Test
    void aLostOptimisticLockReturns409() throws Exception {
        mockMvc.perform(get("/test/optimistic-lock"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.valueOf("application/problem+json")))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.type").value("https://flow-cortex.dev/problems/conflict"));
    }

    @Test
    void validationViolationReturns400NamingTheField() throws Exception {
        mockMvc.perform(post("/test/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.valueOf("application/problem+json")))
                .andExpect(jsonPath("$.errors.name").exists());
    }

    @Test
    void anInvalidRequestParameterReturns400NamingTheParameter() throws Exception {
        mockMvc.perform(get("/test/page").param("size", "500"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.valueOf("application/problem+json")))
                .andExpect(jsonPath("$.errors.size").exists());
    }

    @Test
    void aRequestParameterOfTheWrongTypeReturns400NamingTheParameter() throws Exception {
        mockMvc.perform(get("/test/page").param("size", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.valueOf("application/problem+json")))
                .andExpect(jsonPath("$.errors.size").exists());
    }
}
