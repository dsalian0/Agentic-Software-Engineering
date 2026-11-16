package com.salian.shortener.web;

import com.salian.shortener.link.LinkResolvedEvent;
import com.salian.shortener.link.LinkService;
import com.salian.shortener.link.ShortLink;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.net.URI;
import java.time.Clock;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Redirects live under {@code /r/} so codes can never shadow API, actuator or Swagger paths.
 * 302 (not 301) is deliberate: browsers cache 301s, which would hide future clicks and make it
 * impossible to correct a link's target later.
 */
@RestController
@Tag(name = "Redirect")
public class RedirectController {

    private final LinkService linkService;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public RedirectController(LinkService linkService, ApplicationEventPublisher events, Clock clock) {
        this.linkService = linkService;
        this.events = events;
        this.clock = clock;
    }

    @GetMapping("/r/{code}")
    @Operation(summary = "Redirect to the target URL (302)")
    public ResponseEntity<Void> redirect(@PathVariable String code) {
        ShortLink link = linkService.resolve(code);
        events.publishEvent(new LinkResolvedEvent(link.getId(), link.getCode(), clock.instant()));
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(link.getTargetUrl()))
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .build();
    }
}
