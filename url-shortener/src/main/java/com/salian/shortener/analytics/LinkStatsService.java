package com.salian.shortener.analytics;

import com.salian.shortener.link.LinkService;
import com.salian.shortener.link.ShortLink;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class LinkStatsService {

    static final int DEFAULT_RANGE_DAYS = 30;
    static final int MAX_RANGE_DAYS = 366;
    static final int TOP_REFERRERS = 10;
    static final String DIRECT = "(direct)";

    private final LinkService linkService;
    private final ClickEventRepository repository;
    private final Clock clock;

    public LinkStatsService(LinkService linkService, ClickEventRepository repository, Clock clock) {
        this.linkService = linkService;
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * @param from inclusive UTC date; defaults to {@code to - 29 days}
     * @param to   inclusive UTC date; defaults to today
     */
    public LinkStats stats(String code, LocalDate from, LocalDate to) {
        ShortLink link = linkService.resolve(code);
        LocalDate end = to != null ? to : LocalDate.now(clock.withZone(ZoneOffset.UTC));
        LocalDate start = from != null ? from : end.minusDays(DEFAULT_RANGE_DAYS - 1L);
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("'from' must not be after 'to'");
        }
        if (ChronoUnit.DAYS.between(start, end) + 1 > MAX_RANGE_DAYS) {
            throw new IllegalArgumentException("date range must not exceed " + MAX_RANGE_DAYS + " days");
        }
        Instant fromInstant = start.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant toInstant = end.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        Map<LocalDate, Long> clicksPerDay = new TreeMap<>();
        Map<LocalDate, Set<String>> visitorsPerDay = new HashMap<>();
        long total = 0;
        for (ClickEventRepository.ClickSample sample : repository.findSamples(link.getId(), fromInstant, toInstant)) {
            LocalDate day = sample.getOccurredAt().atZone(ZoneOffset.UTC).toLocalDate();
            clicksPerDay.merge(day, 1L, Long::sum);
            visitorsPerDay.computeIfAbsent(day, d -> new HashSet<>()).add(sample.getVisitorHash());
            total++;
        }
        List<LinkStats.DailyClicks> daily = new ArrayList<>();
        clicksPerDay.forEach((day, clicks) ->
                daily.add(new LinkStats.DailyClicks(day, clicks, visitorsPerDay.get(day).size())));

        List<LinkStats.ReferrerClicks> referrers = repository.countByReferrer(link.getId(), fromInstant, toInstant)
                .stream()
                .limit(TOP_REFERRERS)
                .map(r -> new LinkStats.ReferrerClicks(r.getReferrerHost() == null ? DIRECT : r.getReferrerHost(), r.getClicks()))
                .toList();

        Map<String, Long> families = new TreeMap<>();
        repository.countByUserAgentFamily(link.getId(), fromInstant, toInstant)
                .forEach(f -> families.put(f.getUserAgentFamily(), f.getClicks()));

        return new LinkStats(link.getCode(), start, end, total, daily, referrers, families);
    }
}
