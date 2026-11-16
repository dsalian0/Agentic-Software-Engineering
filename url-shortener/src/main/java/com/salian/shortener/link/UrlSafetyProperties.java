package com.salian.shortener.link;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param blockedDomains domains (and their subdomains) that may never be shortened
 */
@ConfigurationProperties(prefix = "shortener.safety")
public record UrlSafetyProperties(@DefaultValue({}) List<String> blockedDomains) {

    public UrlSafetyProperties {
        blockedDomains = blockedDomains == null ? List.of() : List.copyOf(blockedDomains);
    }
}
