package com.salian.shortener.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Core shortener settings.
 *
 * @param baseUrl               public base URL used to build {@code shortUrl} values (no trailing slash)
 * @param codeLength            length of generated Base62 codes (7 chars = ~3.5e12 combinations)
 * @param maxGenerationAttempts bounded retries when a generated code collides with an existing one
 */
@Validated
@ConfigurationProperties(prefix = "shortener")
public record ShortenerProperties(
        @NotBlank @DefaultValue("http://localhost:8080") String baseUrl,
        @Min(4) @Max(32) @DefaultValue("7") int codeLength,
        @Min(1) @Max(20) @DefaultValue("5") int maxGenerationAttempts) {

    public ShortenerProperties {
        if (baseUrl != null && baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
    }

    public String shortUrlFor(String code) {
        return baseUrl + "/r/" + code;
    }
}
