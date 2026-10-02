package com.flowcortex.workflow.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.server.ResponseStatusException;

class AdminSurfaceInterceptorTest {

    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/admin/runs");
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @Test
    void anEnabledConsoleLetsTheRequestThrough() throws Exception {
        assertThat(new AdminSurfaceInterceptor(true).preHandle(request, response, new Object())).isTrue();
    }

    @Test
    void aDisabledConsoleRefusesWith503AndSaysWhy() {
        assertThatThrownBy(() -> new AdminSurfaceInterceptor(false).preHandle(request, response, new Object()))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(e.getReason()).isEqualTo("Admin console is currently disabled");
                });
    }
}
