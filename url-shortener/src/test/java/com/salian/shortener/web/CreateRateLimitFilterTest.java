package com.salian.shortener.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CreateRateLimitFilterTest {

    private final AtomicLong nanos = new AtomicLong(0);

    private static MockHttpServletRequest create(String client) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/links");
        request.setRemoteAddr(client);
        return request;
    }

    private static MockHttpServletResponse send(CreateRateLimitFilter filter, MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    @Test
    void allowsBurstThenReturns429WithRetryAfterThenRefills() throws Exception {
        CreateRateLimitFilter filter = new CreateRateLimitFilter(new RateLimitProperties(true, 2, 60, 100), nanos::get);

        assertThat(send(filter, create("198.51.100.1")).getStatus()).isEqualTo(200);
        assertThat(send(filter, create("198.51.100.1")).getStatus()).isEqualTo(200);
        MockHttpServletResponse limited = send(filter, create("198.51.100.1"));
        assertThat(limited.getStatus()).isEqualTo(429);
        assertThat(limited.getHeader("Retry-After")).isEqualTo("1");
        assertThat(limited.getContentAsString()).contains("\"status\":429");

        // A different client has its own bucket.
        assertThat(send(filter, create("198.51.100.2")).getStatus()).isEqualTo(200);

        nanos.addAndGet(1_000_000_000L); // 1s at 60/min refills one token
        assertThat(send(filter, create("198.51.100.1")).getStatus()).isEqualTo(200);
    }

    @Test
    void ignoresRedirectsReadsAndDisabledMode() throws Exception {
        CreateRateLimitFilter filter = new CreateRateLimitFilter(new RateLimitProperties(true, 1, 1, 100), nanos::get);
        for (int i = 0; i < 5; i++) {
            assertThat(send(filter, new MockHttpServletRequest("GET", "/r/abc")).getStatus()).isEqualTo(200);
            assertThat(send(filter, new MockHttpServletRequest("GET", "/api/v1/links")).getStatus()).isEqualTo(200);
        }
        CreateRateLimitFilter disabled = new CreateRateLimitFilter(new RateLimitProperties(false, 1, 1, 100), nanos::get);
        for (int i = 0; i < 5; i++) {
            assertThat(send(disabled, create("198.51.100.9")).getStatus()).isEqualTo(200);
        }
    }

    @Test
    void evictsIdleClientsWhenTrackingCapIsReached() throws Exception {
        CreateRateLimitFilter filter = new CreateRateLimitFilter(new RateLimitProperties(true, 5, 5, 100), nanos::get);
        for (int i = 0; i < 100; i++) {
            send(filter, create("203.0.113." + i));
        }
        nanos.addAndGet(11L * 60 * 1_000_000_000L);
        send(filter, create("198.51.100.200"));

        assertThat(filter.trackedClients()).isEqualTo(1);
    }
}
