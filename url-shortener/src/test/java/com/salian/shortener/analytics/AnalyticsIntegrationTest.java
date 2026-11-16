package com.salian.shortener.analytics;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** Redirect -> event -> buffered recorder -> click_events -> stats endpoint, end to end on H2. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class AnalyticsIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ClickRecorder clickRecorder;

    @Test
    void redirectsAreCountedAndAggregated() throws Exception {
        mockMvc.perform(post("/api/v1/links").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetUrl\":\"https://example.com/launch\",\"customAlias\":\"launch-day\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/r/launch-day").header("Referer", "https://news.example.org/post/1")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0)")).andExpect(status().isFound());
        mockMvc.perform(get("/r/launch-day").header("Referer", "https://news.example.org/post/2")
                .header("User-Agent", "Mozilla/5.0 (iPhone) Mobile")).andExpect(status().isFound());
        mockMvc.perform(get("/r/launch-day")).andExpect(status().isFound());
        clickRecorder.flush();

        String today = LocalDate.now(ZoneOffset.UTC).toString();
        mockMvc.perform(get("/api/v1/links/launch-day/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalClicks").value(3))
                .andExpect(jsonPath("$.daily[0].date").value(today))
                .andExpect(jsonPath("$.daily[0].clicks").value(3))
                // MockMvc uses one client address, so all clicks are one visitor that day.
                .andExpect(jsonPath("$.daily[0].uniqueVisitors").value(1))
                .andExpect(jsonPath("$.topReferrers[0].referrer").value("news.example.org"))
                .andExpect(jsonPath("$.topReferrers[0].clicks").value(2))
                .andExpect(jsonPath("$.userAgentFamilies.DESKTOP").value(1))
                .andExpect(jsonPath("$.userAgentFamilies.MOBILE").value(1))
                .andExpect(jsonPath("$.userAgentFamilies.UNKNOWN").value(1));
    }

    @Test
    void statsValidateRangeAndUnknownLinks() throws Exception {
        mockMvc.perform(get("/api/v1/links/nope-nope/stats")).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/links").contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetUrl\":\"https://example.com\",\"customAlias\":\"range-check\"}"));
        mockMvc.perform(get("/api/v1/links/range-check/stats?from=2026-11-10&to=2026-11-01"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/links/range-check/stats?from=2020-01-01&to=2026-11-01"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/links/range-check/stats?from=not-a-date"))
                .andExpect(status().isBadRequest());
    }
}
