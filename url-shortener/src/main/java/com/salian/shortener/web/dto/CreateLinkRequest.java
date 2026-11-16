package com.salian.shortener.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "Payload for creating a short link")
public record CreateLinkRequest(

        @Schema(example = "https://example.com/articles/42?utm_source=newsletter")
        @NotBlank
        @Size(max = 2048)
        String targetUrl,

        @Schema(description = "Optional custom code; 4-32 chars of letters, digits, '-' or '_'", example = "spring-sale")
        @Size(min = 4, max = 32)
        @Pattern(regexp = "[A-Za-z0-9_-]+", message = "may contain only letters, digits, '-' and '_'")
        String customAlias) {
}
