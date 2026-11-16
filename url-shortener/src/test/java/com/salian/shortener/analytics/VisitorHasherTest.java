package com.salian.shortener.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class VisitorHasherTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 15);

    @Test
    void sameVisitorSameDayHashesEqualAndNeverContainsRawAddress() {
        VisitorHasher hasher = new VisitorHasher("salt");

        String first = hasher.hash("203.0.113.7", DAY);

        assertThat(first).isEqualTo(hasher.hash("203.0.113.7", DAY)).hasSize(64).doesNotContain("203.0.113.7");
    }

    @Test
    void hashRotatesDailyAndDependsOnSalt() {
        VisitorHasher hasher = new VisitorHasher("salt");

        assertThat(hasher.hash("203.0.113.7", DAY)).isNotEqualTo(hasher.hash("203.0.113.7", DAY.plusDays(1)));
        assertThat(hasher.hash("203.0.113.7", DAY)).isNotEqualTo(new VisitorHasher("other").hash("203.0.113.7", DAY));
        assertThat(hasher.hash("203.0.113.7", DAY)).isNotEqualTo(hasher.hash("203.0.113.8", DAY));
    }

    @Test
    void blankConfiguredSaltFallsBackToRandomSalt() {
        VisitorHasher a = new VisitorHasher(new AnalyticsProperties(10, 10, 100, ""));
        VisitorHasher b = new VisitorHasher(new AnalyticsProperties(10, 10, 100, ""));

        assertThat(a.hash("x", DAY)).isNotEqualTo(b.hash("x", DAY));
    }
}
