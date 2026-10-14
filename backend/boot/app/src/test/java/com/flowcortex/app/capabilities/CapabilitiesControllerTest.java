package com.flowcortex.app.capabilities;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.flowcortex.media.api.MediaJobs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(CapabilitiesController.class)
class CapabilitiesControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MediaJobs mediaJobs;

    @BeforeEach
    void enableMedia() {
        when(mediaJobs.isEnabled()).thenReturn(true);
    }

    @Test
    void capabilitiesReflectsBothFlags() throws Exception {
        mockMvc.perform(get("/api/v1/capabilities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mediaEnabled").value(true))
                .andExpect(jsonPath("$.productVideoEnabled").value(false));
    }

    @Test
    void capabilitiesNameTheSeededProductVideoWorkflow() throws Exception {
        mockMvc.perform(get("/api/v1/capabilities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productVideoWorkflowId").value("9aa35334-b672-4b6c-93e8-1a4c2cc14301"));
    }
}
