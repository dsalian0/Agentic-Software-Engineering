package com.salian.shortener.link;

import java.time.Instant;

/**
 * Published on every successful redirect. Listeners must be fast and must never throw: the redirect
 * path does not wait for, or fail because of, downstream consumers. (Extension point; no consumers yet.)
 */
public record LinkResolvedEvent(Long linkId, String code, Instant occurredAt) {
}
