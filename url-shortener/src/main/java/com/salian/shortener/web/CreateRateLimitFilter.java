package com.salian.shortener.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Throttles {@code POST /api/v1/links} per client address with an in-memory token bucket and answers
 * 429 + {@code Retry-After} when a client exceeds it. Redirects and reads are never throttled.
 * Limitation (documented): state is per instance; a multi-instance deployment needs a shared store.
 * Registered by {@link RateLimitConfig} rather than component scanning so web-slice tests stay isolated.
 */
public class CreateRateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(CreateRateLimitFilter.class);
    private static final long NANOS_PER_MINUTE = 60_000_000_000L;
    private static final long IDLE_EVICTION_NANOS = 10 * NANOS_PER_MINUTE;

    private final RateLimitProperties properties;
    private final LongSupplier nanoClock;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public CreateRateLimitFilter(RateLimitProperties properties) {
        this(properties, System::nanoTime);
    }

    CreateRateLimitFilter(RateLimitProperties properties, LongSupplier nanoClock) {
        this.properties = properties;
        this.nanoClock = nanoClock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !properties.enabled()
                || !"POST".equalsIgnoreCase(request.getMethod())
                || !"/api/v1/links".equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long now = nanoClock.getAsLong();
        String client = request.getRemoteAddr();
        evictIfNeeded(now);
        Bucket bucket = buckets.computeIfAbsent(client, key -> new Bucket(properties.capacity(), now));
        long retryAfterSeconds = bucket.tryConsume(now, properties.capacity(), properties.refillPerMinute());
        if (retryAfterSeconds == 0) {
            chain.doFilter(request, response);
            return;
        }
        log.warn("Rate limit exceeded for link creation client={} retryAfter={}s", client, retryAfterSeconds);
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Too many requests\",\"status\":429,"
                + "\"detail\":\"Link creation rate limit exceeded; retry after " + retryAfterSeconds + " seconds\"}");
    }

    private void evictIfNeeded(long now) {
        if (buckets.size() >= properties.maxTrackedClients()) {
            buckets.entrySet().removeIf(entry -> now - entry.getValue().lastSeen() > IDLE_EVICTION_NANOS);
        }
    }

    int trackedClients() {
        return buckets.size();
    }

    private static final class Bucket {
        private double tokens;
        private long lastRefill;
        private volatile long lastSeen;

        Bucket(int capacity, long now) {
            this.tokens = capacity;
            this.lastRefill = now;
            this.lastSeen = now;
        }

        /** @return 0 if a token was consumed, otherwise seconds until one is available */
        synchronized long tryConsume(long now, int capacity, int refillPerMinute) {
            lastSeen = now;
            double refill = (now - lastRefill) * refillPerMinute / (double) NANOS_PER_MINUTE;
            tokens = Math.min(capacity, tokens + refill);
            lastRefill = now;
            if (tokens >= 1) {
                tokens -= 1;
                return 0;
            }
            double secondsUntilToken = (1 - tokens) * 60.0 / refillPerMinute;
            return Math.max(1, (long) Math.ceil(secondsUntilToken));
        }

        long lastSeen() {
            return lastSeen;
        }
    }
}
