package com.salian.shortener.web;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Per-client token bucket for link creation.
 *
 * @param capacity         burst size (tokens in a full bucket)
 * @param refillPerMinute  tokens restored per minute
 * @param maxTrackedClients cap on tracked clients; idle buckets are evicted beyond it (bounded memory)
 */
@Validated
@ConfigurationProperties(prefix = "shortener.rate-limit")
public record RateLimitProperties(
        @DefaultValue("true") boolean enabled,
        @Min(1) @DefaultValue("30") int capacity,
        @Min(1) @DefaultValue("30") int refillPerMinute,
        @Min(100) @DefaultValue("10000") int maxTrackedClients) {
}
