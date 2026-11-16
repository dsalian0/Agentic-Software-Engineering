package com.salian.shortener.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.salian.shortener.config.ShortenerProperties;
import com.salian.shortener.link.AliasAlreadyExistsException;
import com.salian.shortener.link.InvalidTargetUrlException;
import com.salian.shortener.link.LinkNotFoundException;
import com.salian.shortener.link.LinkService;
import com.salian.shortener.link.ShortLink;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(LinkController.class)
@Import(LinkControllerTest.Config.class)
class LinkControllerTest {

    @TestConfiguration
    @EnableConfigurationProperties(ShortenerProperties.class)
    static class Config {
    }

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private LinkService linkService;

    private static ShortLink link(String code) {
        return ShortLink.builder().id(1L).code(code).targetUrl("https://example.com/a")
                .createdAt(Instant.parse("2026-11-15T12:00:00Z")).build();
    }

    @Test
    void createReturns201WithLocationAndShortUrl() throws Exception {
        when(linkService.create(eq("https://example.com/a"), isNull())).thenReturn(link("Ab3dE9x"));

        mockMvc.perform(post("/api/v1/links").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetUrl\":\"https://example.com/a\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/links/Ab3dE9x"))
                .andExpect(jsonPath("$.code").value("Ab3dE9x"))
                .andExpect(jsonPath("$.shortUrl").value("http://localhost:8080/r/Ab3dE9x"))
                .andExpect(jsonPath("$.targetUrl").value("https://example.com/a"));
    }

    @Test
    void createRejectsMissingTargetAndBadAlias() throws Exception {
        mockMvc.perform(post("/api/v1/links").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customAlias\":\"bad alias!\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("targetUrl")))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("customAlias")));
    }

    @Test
    void createMapsInvalidTargetTo400() throws Exception {
        when(linkService.create(any(), any())).thenThrow(new InvalidTargetUrlException("targetUrl scheme must be http or https"));

        mockMvc.perform(post("/api/v1/links").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetUrl\":\"ftp://example.com\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid target URL"));
    }

    @Test
    void createMapsAliasConflictTo409() throws Exception {
        when(linkService.create(any(), eq("spring-sale"))).thenThrow(new AliasAlreadyExistsException("spring-sale"));

        mockMvc.perform(post("/api/v1/links").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetUrl\":\"https://example.com\",\"customAlias\":\"spring-sale\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Alias already in use"));
    }

    @Test
    void getReturnsLinkOr404() throws Exception {
        when(linkService.resolve("Ab3dE9x")).thenReturn(link("Ab3dE9x"));
        when(linkService.resolve("missing")).thenThrow(new LinkNotFoundException("missing"));

        mockMvc.perform(get("/api/v1/links/Ab3dE9x"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("Ab3dE9x"));
        mockMvc.perform(get("/api/v1/links/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Link not found"));
    }

    @Test
    void listReturnsStablePageShape() throws Exception {
        when(linkService.list(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(link("one1111"), link("two2222")), PageRequest.of(0, 20), 2));

        mockMvc.perform(get("/api/v1/links"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20));
    }
}
