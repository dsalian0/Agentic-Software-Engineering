package com.salian.shortener.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** Full stack: HTTP -> service -> JPA -> Flyway-migrated H2 (PostgreSQL mode). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class RedirectFlowIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void createThenLookupThenRedirect() throws Exception {
        String body = mockMvc.perform(post("/api/v1/links").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetUrl\":\"https://example.com/docs?page=2\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode created = objectMapper.readTree(body);
        String code = created.get("code").asText();
        assertThat(code).hasSize(7);

        mockMvc.perform(get("/api/v1/links/" + code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetUrl").value("https://example.com/docs?page=2"));

        mockMvc.perform(get("/r/" + code))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/docs?page=2"))
                .andExpect(header().string("Cache-Control", "private, no-store"));
    }

    @Test
    void customAliasIsUniqueAcrossRequests() throws Exception {
        String payload = "{\"targetUrl\":\"https://example.com\",\"customAlias\":\"unique-alias\"}";
        mockMvc.perform(post("/api/v1/links").contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/links").contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isConflict());
        mockMvc.perform(get("/r/unique-alias")).andExpect(status().isFound());
    }

    @Test
    void unknownCodeReturns404ProblemDetail() throws Exception {
        mockMvc.perform(get("/r/doesNotExist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Link not found"));
    }

    @Test
    void healthEndpointIsUp() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
