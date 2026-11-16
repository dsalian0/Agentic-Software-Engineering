package com.salian.shortener.link;

import java.net.URI;
import java.util.Locale;
import java.util.Set;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Baseline rule: only absolute http(s) URLs with a host can be shortened. */
@Component
@Order(0)
public class BasicTargetUrlPolicy implements TargetUrlPolicy {

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    @Override
    public void check(URI target) {
        if (!target.isAbsolute() || target.getScheme() == null) {
            throw new InvalidTargetUrlException("targetUrl must be an absolute URL");
        }
        if (!ALLOWED_SCHEMES.contains(target.getScheme().toLowerCase(Locale.ROOT))) {
            throw new InvalidTargetUrlException("targetUrl scheme must be http or https");
        }
        if (target.getHost() == null || target.getHost().isBlank()) {
            throw new InvalidTargetUrlException("targetUrl must contain a host");
        }
    }
}
