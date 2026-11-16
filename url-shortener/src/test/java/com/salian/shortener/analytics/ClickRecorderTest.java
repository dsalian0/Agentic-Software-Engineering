package com.salian.shortener.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.salian.shortener.link.LinkResolvedEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ClickRecorderTest {

    private final ClickEventRepository repository = mock(ClickEventRepository.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private ClickRecorder recorder;

    private ClickRecorder recorder(int capacity) {
        // Long flush interval: the test drives flushing explicitly.
        recorder = new ClickRecorder(repository, new VisitorHasher("salt"),
                new AnalyticsProperties(capacity, 2, 3_600_000, "salt"), meters);
        return recorder;
    }

    @AfterEach
    void stop() {
        if (recorder != null) {
            recorder.shutdown();
        }
    }

    private static LinkResolvedEvent event(String referrer, String ua) {
        return new LinkResolvedEvent(1L, "abc1234", Instant.parse("2026-11-15T10:00:00Z"), referrer, ua, "203.0.113.7");
    }

    @Test
    @SuppressWarnings("unchecked")
    void flushPersistsInBatchesWithoutRawAddress() {
        ClickRecorder clicks = recorder(10);
        clicks.onLinkResolved(event("https://News.Example.com/story", "curl/8.0"));
        clicks.onLinkResolved(event(null, null));
        clicks.onLinkResolved(event("not a uri", "Mozilla/5.0 (X11)"));

        assertThat(clicks.flush()).isEqualTo(3);

        ArgumentCaptor<List<ClickEvent>> batches = ArgumentCaptor.forClass(List.class);
        verify(repository, times(2)).saveAll(batches.capture());
        List<ClickEvent> rows = batches.getAllValues().stream().flatMap(List::stream).toList();
        assertThat(rows).extracting(ClickEvent::getReferrerHost).containsExactly("news.example.com", null, null);
        assertThat(rows).extracting(ClickEvent::getUserAgentFamily).containsExactly("BOT", "UNKNOWN", "DESKTOP");
        assertThat(rows).allSatisfy(row -> assertThat(row.getVisitorHash()).hasSize(64).doesNotContain("203.0.113.7"));
        assertThat(meters.counter("shortener.clicks.recorded").count()).isEqualTo(3);
    }

    @Test
    void dropsAndCountsWhenBufferIsFull() {
        ClickRecorder clicks = recorder(2);
        clicks.onLinkResolved(event(null, null));
        clicks.onLinkResolved(event(null, null));
        clicks.onLinkResolved(event(null, null));

        assertThat(clicks.pending()).isEqualTo(2);
        assertThat(meters.counter("shortener.clicks.dropped").count()).isEqualTo(1);
    }

    @Test
    void retriesFailedBatchOnceThenDropsWithoutThrowing() {
        when(repository.saveAll(anyList())).thenThrow(new IllegalStateException("db down"));
        ClickRecorder clicks = recorder(10);
        clicks.onLinkResolved(event(null, null));

        assertThat(clicks.flush()).isZero();
        verify(repository, times(2)).saveAll(anyList());
        assertThat(meters.counter("shortener.clicks.dropped").count()).isEqualTo(1);
    }
}
