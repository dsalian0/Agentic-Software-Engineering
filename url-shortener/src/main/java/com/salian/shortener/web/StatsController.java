package com.salian.shortener.web;

import com.salian.shortener.analytics.LinkStats;
import com.salian.shortener.analytics.LinkStatsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Analytics", description = "Click analytics per short link")
public class StatsController {

    private final LinkStatsService statsService;

    public StatsController(LinkStatsService statsService) {
        this.statsService = statsService;
    }

    @GetMapping("/api/v1/links/{code}/stats")
    @Operation(summary = "Click statistics for a link over an inclusive UTC date range (default: last 30 days)")
    public LinkStats stats(@PathVariable String code,
                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return statsService.stats(code, from, to);
    }
}
