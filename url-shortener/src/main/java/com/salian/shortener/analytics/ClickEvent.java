package com.salian.shortener.analytics;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** One recorded redirect. Holds no raw IP address: only a salted, day-scoped visitor hash. */
@Entity
@Table(name = "click_events")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClickEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "link_id", nullable = false)
    private Long linkId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "referrer_host", length = 255)
    private String referrerHost;

    @Column(name = "user_agent_family", nullable = false, length = 16)
    private String userAgentFamily;

    @Column(name = "visitor_hash", nullable = false, length = 64)
    private String visitorHash;
}
