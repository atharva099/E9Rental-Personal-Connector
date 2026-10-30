package com.e9rental.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.e9rental.config.GoogleSandboxPolicy;
import com.e9rental.config.LocalApiGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class CapabilitiesControllerTest {
    private static final String TOKEN = "synthetic-capabilities-token-value-000000000000";
    private GoogleSandboxPolicy policy;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        policy = mock(GoogleSandboxPolicy.class);
        mvc = MockMvcBuilders.standaloneSetup(new CapabilitiesController(policy))
                .addFilters(new LocalApiGuard(TOKEN))
                .build();
    }

    @Test
    void authenticatedPersonalAndSandboxResponsesExposeOnlyModeAndAreNotCached() throws Exception {
        when(policy.mode()).thenReturn("PERSONAL", "SANDBOX");

        var personal = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/capabilities").header("Authorization", "Bearer " + TOKEN))
                .andReturn().getResponse();
        assertThat(personal.getStatus()).isEqualTo(200);
        assertThat(personal.getContentAsString()).isEqualTo("{\"mode\":\"PERSONAL\"}");
        assertThat(personal.getHeader("Cache-Control")).isEqualTo("no-store");

        var sandbox = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/capabilities").header("Authorization", "Bearer " + TOKEN))
                .andReturn().getResponse();
        assertThat(sandbox.getStatus()).isEqualTo(200);
        assertThat(sandbox.getContentAsString()).isEqualTo("{\"mode\":\"SANDBOX\"}");
        assertThat(sandbox.getHeader("Cache-Control")).isEqualTo("no-store");
        verify(policy, org.mockito.Mockito.times(2)).mode();
        verifyNoMoreInteractions(policy);
    }

    @Test
    void missingOrInvalidBearerTokenIsRejectedBeforeModeLookup() throws Exception {
        var missing = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/capabilities"))
                .andReturn().getResponse();
        assertThat(missing.getStatus()).isEqualTo(401);
        assertThat(missing.getContentAsString()).isEqualTo("{\"error\":\"Local bearer token required\"}");
        assertThat(missing.getHeader("Cache-Control")).isEqualTo("no-store");

        var invalid = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/capabilities").header("Authorization", "Bearer invalid"))
                .andReturn().getResponse();
        assertThat(invalid.getStatus()).isEqualTo(401);
        verifyNoMoreInteractions(policy);
    }

    @Test
    void unsupportedModeFailsClosedWithFixedResponse() throws Exception {
        when(policy.mode()).thenReturn("UNKNOWN");
        var response = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/capabilities").header("Authorization", "Bearer " + TOKEN))
                .andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).isEqualTo("{\"error\":\"Backend mode is unavailable\"}");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        verify(policy).mode();
        verifyNoMoreInteractions(policy);
    }
}
