package com.salian.shortener.analytics;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Click analytics for one link over an inclusive UTC date range.
 * {@code uniqueVisitors} is per day (visitor hashes rotate daily by design), so it is not summed.
 */
public record LinkStats(
        String code,
        LocalDate from,
        LocalDate to,
        long totalClicks,
        List<DailyClicks> daily,
        List<ReferrerClicks> topReferrers,
        Map<String, Long> userAgentFamilies) {

    public record DailyClicks(LocalDate date, long clicks, long uniqueVisitors) {
    }

    public record ReferrerClicks(String referrer, long clicks) {
    }
}
