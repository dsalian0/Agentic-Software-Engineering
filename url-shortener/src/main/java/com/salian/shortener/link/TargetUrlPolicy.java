package com.salian.shortener.link;

import java.net.URI;

/**
 * Extension point for validating link targets. Every Spring bean implementing this interface is
 * applied (in {@link org.springframework.core.annotation.Order} order) before a link is stored,
 * so new safety rules can be added without modifying {@link LinkService}.
 */
public interface TargetUrlPolicy {

    /**
     * @throws InvalidTargetUrlException if the target must be rejected
     */
    void check(URI target);
}
