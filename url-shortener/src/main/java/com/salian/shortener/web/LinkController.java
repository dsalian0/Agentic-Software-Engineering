package com.salian.shortener.web;

import com.salian.shortener.config.ShortenerProperties;
import com.salian.shortener.link.LinkService;
import com.salian.shortener.link.ShortLink;
import com.salian.shortener.web.dto.CreateLinkRequest;
import com.salian.shortener.web.dto.LinkResponse;
import com.salian.shortener.web.dto.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/links")
@Tag(name = "Links", description = "Create and look up short links")
public class LinkController {

    private final LinkService linkService;
    private final ShortenerProperties properties;

    public LinkController(LinkService linkService, ShortenerProperties properties) {
        this.linkService = linkService;
        this.properties = properties;
    }

    @PostMapping
    @Operation(summary = "Create a short link (optionally with a custom alias)")
    public ResponseEntity<LinkResponse> create(@Valid @RequestBody CreateLinkRequest request) {
        ShortLink link = linkService.create(request.targetUrl(), request.customAlias());
        return ResponseEntity.created(URI.create("/api/v1/links/" + link.getCode())).body(toResponse(link));
    }

    @GetMapping("/{code}")
    @Operation(summary = "Look up a short link by code")
    public LinkResponse get(@PathVariable String code) {
        return toResponse(linkService.resolve(code));
    }

    @GetMapping
    @Operation(summary = "List short links (paginated, newest first by default)")
    public PageResponse<LinkResponse> list(
            @ParameterObject @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable) {
        return PageResponse.from(linkService.list(pageable), this::toResponse);
    }

    private LinkResponse toResponse(ShortLink link) {
        return LinkResponse.from(link, properties.shortUrlFor(link.getCode()));
    }
}
