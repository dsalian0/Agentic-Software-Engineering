package com.salian.shortener.analytics;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ClickEventRepository extends JpaRepository<ClickEvent, Long> {

    /** Minimal projection for per-day aggregation (keeps the stats query portable across databases). */
    interface ClickSample {
        Instant getOccurredAt();

        String getVisitorHash();
    }

    interface ReferrerCount {
        String getReferrerHost();

        long getClicks();
    }

    interface FamilyCount {
        String getUserAgentFamily();

        long getClicks();
    }

    @Query("""
            select c.occurredAt as occurredAt, c.visitorHash as visitorHash
            from ClickEvent c
            where c.linkId = :linkId and c.occurredAt >= :from and c.occurredAt < :to
            """)
    List<ClickSample> findSamples(@Param("linkId") Long linkId, @Param("from") Instant from, @Param("to") Instant to);

    @Query("""
            select c.referrerHost as referrerHost, count(c) as clicks
            from ClickEvent c
            where c.linkId = :linkId and c.occurredAt >= :from and c.occurredAt < :to
            group by c.referrerHost
            order by count(c) desc
            """)
    List<ReferrerCount> countByReferrer(@Param("linkId") Long linkId, @Param("from") Instant from, @Param("to") Instant to);

    @Query("""
            select c.userAgentFamily as userAgentFamily, count(c) as clicks
            from ClickEvent c
            where c.linkId = :linkId and c.occurredAt >= :from and c.occurredAt < :to
            group by c.userAgentFamily
            """)
    List<FamilyCount> countByUserAgentFamily(@Param("linkId") Long linkId, @Param("from") Instant from, @Param("to") Instant to);
}
