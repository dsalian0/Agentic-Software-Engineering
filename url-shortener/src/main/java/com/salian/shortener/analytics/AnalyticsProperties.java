package com.salian.shortener.analytics;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * @param queueCapacity   bounded buffer between the redirect path and the database; when full, clicks
 *                        are dropped (and counted) rather than slowing redirects down
 * @param batchSize       maximum click events written per flush
 * @param flushIntervalMs how often the background writer drains the buffer
 * @param visitorSalt     secret mixed into visitor hashes; blank = random per process (uniques reset on restart)
 */
@Validated
@ConfigurationProperties(prefix = "shortener.analytics")
public record AnalyticsProperties(
        @Min(1) @DefaultValue("10000") int queueCapacity,
        @Min(1) @DefaultValue("500") int batchSize,
        @Min(10) @DefaultValue("1000") long flushIntervalMs,
        @DefaultValue("") String visitorSalt) {
}
