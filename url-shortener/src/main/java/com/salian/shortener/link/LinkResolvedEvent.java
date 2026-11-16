package com.salian.shortener.link;

import java.time.Instant;

/**
 * Published on every successful redirect. Listeners (e.g. analytics) must be fast and must never
 * throw: the redirect path does not wait for, or fail because of, downstream consumers.
 *
 * @param referrer      raw {@code Referer} header, may be null
 * @param userAgent     raw {@code User-Agent} header, may be null
 * @param clientAddress remote address; consumers must not persist it in raw form (PII)
 */
public record LinkResolvedEvent(
        Long linkId,
        String code,
        Instant occurredAt,
        String referrer,
        String userAgent,
        String clientAddress) {
}
