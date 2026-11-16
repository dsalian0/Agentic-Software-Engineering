package com.salian.shortener.analytics;

import com.salian.shortener.link.LinkResolvedEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Records clicks off the redirect path. The listener only does a non-blocking {@code offer} into a
 * bounded queue; a single background thread batches inserts. Trade-off (documented): availability
 * of redirects wins over analytics completeness. When the queue is full, or a batch insert fails
 * twice, clicks are dropped and counted ({@code shortener.clicks.dropped}). A production system
 * would use a transactional outbox or a log (e.g. Kafka) instead of process memory.
 */
@Component
public class ClickRecorder {

    private static final Logger log = LoggerFactory.getLogger(ClickRecorder.class);

    private final ClickEventRepository repository;
    private final VisitorHasher visitorHasher;
    private final int batchSize;
    private final BlockingQueue<LinkResolvedEvent> queue;
    private final ScheduledExecutorService writer;
    private final Counter recorded;
    private final Counter dropped;

    public ClickRecorder(ClickEventRepository repository,
                         VisitorHasher visitorHasher,
                         AnalyticsProperties properties,
                         MeterRegistry meterRegistry) {
        this.repository = repository;
        this.visitorHasher = visitorHasher;
        this.batchSize = properties.batchSize();
        this.queue = new ArrayBlockingQueue<>(properties.queueCapacity());
        this.recorded = meterRegistry.counter("shortener.clicks.recorded");
        this.dropped = meterRegistry.counter("shortener.clicks.dropped");
        meterRegistry.gaugeCollectionSize("shortener.clicks.queue.size", List.of(), queue);
        this.writer = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "click-writer");
            thread.setDaemon(true);
            return thread;
        });
        this.writer.scheduleWithFixedDelay(this::flushSafely, properties.flushIntervalMs(),
                properties.flushIntervalMs(), TimeUnit.MILLISECONDS);
    }

    @EventListener
    public void onLinkResolved(LinkResolvedEvent event) {
        if (!queue.offer(event)) {
            dropped.increment();
            log.warn("Click buffer full, dropping click code={}", event.code());
        }
    }

    /** Drains the buffer synchronously. Public so tests and shutdown can force a flush. */
    public synchronized int flush() {
        int written = 0;
        List<LinkResolvedEvent> batch = new ArrayList<>(batchSize);
        while (queue.drainTo(batch, batchSize) > 0) {
            written += persist(batch);
            batch.clear();
        }
        return written;
    }

    int pending() {
        return queue.size();
    }

    @PreDestroy
    void shutdown() {
        writer.shutdown();
        int written = flush();
        log.info("Click recorder stopped, flushed {} pending clicks", written);
    }

    private void flushSafely() {
        try {
            flush();
        } catch (RuntimeException ex) {
            log.error("Unexpected failure in click writer", ex);
        }
    }

    private int persist(List<LinkResolvedEvent> batch) {
        List<ClickEvent> rows = batch.stream().map(this::toRow).toList();
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                repository.saveAll(rows);
                recorded.increment(rows.size());
                return rows.size();
            } catch (RuntimeException ex) {
                log.warn("Failed to persist {} clicks attempt={}/2: {}", rows.size(), attempt, ex.getMessage());
            }
        }
        dropped.increment(rows.size());
        log.error("Dropped {} clicks after retry", rows.size());
        return 0;
    }

    private ClickEvent toRow(LinkResolvedEvent event) {
        return ClickEvent.builder()
                .linkId(event.linkId())
                .occurredAt(event.occurredAt())
                .referrerHost(referrerHost(event.referrer()))
                .userAgentFamily(UserAgentClassifier.classify(event.userAgent()))
                .visitorHash(visitorHasher.hash(event.clientAddress(),
                        event.occurredAt().atZone(ZoneOffset.UTC).toLocalDate()))
                .build();
    }

    static String referrerHost(String referrer) {
        if (referrer == null || referrer.isBlank()) {
            return null;
        }
        try {
            String host = URI.create(referrer.trim()).getHost();
            return host == null ? null : truncate(host.toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static String truncate(String value) {
        return value.length() <= 255 ? value : value.substring(0, 255);
    }
}
