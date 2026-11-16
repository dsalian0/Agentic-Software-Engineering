package com.salian.shortener.web.dto;

import com.salian.shortener.link.ShortLink;
import java.time.Instant;

public record LinkResponse(String code, String shortUrl, String targetUrl, Instant createdAt) {

    public static LinkResponse from(ShortLink link, String shortUrl) {
        return new LinkResponse(link.getCode(), shortUrl, link.getTargetUrl(), link.getCreatedAt());
    }
}
