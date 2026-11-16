package com.salian.shortener.link;

import com.salian.shortener.config.ShortenerProperties;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LinkService {

    private static final Logger log = LoggerFactory.getLogger(LinkService.class);

    static final int MAX_URL_LENGTH = 2048;

    private final ShortLinkRepository repository;
    private final CodeGenerator codeGenerator;
    private final List<TargetUrlPolicy> policies;
    private final ShortenerProperties properties;
    private final Clock clock;

    public LinkService(ShortLinkRepository repository,
                       CodeGenerator codeGenerator,
                       List<TargetUrlPolicy> policies,
                       ShortenerProperties properties,
                       Clock clock) {
        this.repository = repository;
        this.codeGenerator = codeGenerator;
        this.policies = List.copyOf(policies);
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Creates a short link. Deliberately not wrapped in one transaction: each insert attempt runs in
     * the repository's own transaction so a unique-constraint violation (a concurrent writer took the
     * same code) can be retried with a fresh code instead of poisoning the surrounding transaction.
     */
    public ShortLink create(String targetUrl, String customAlias) {
        URI target = parseAndCheck(targetUrl);
        if (customAlias != null) {
            return createWithAlias(target, customAlias);
        }
        int maxAttempts = properties.maxGenerationAttempts();
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            String code = codeGenerator.next();
            if (repository.existsByCode(code)) {
                log.warn("Generated code collided code={} attempt={}/{}", code, attempt, maxAttempts);
                continue;
            }
            try {
                ShortLink saved = repository.saveAndFlush(newLink(code, target));
                log.info("Created short link id={} code={} host={}", saved.getId(), code, target.getHost());
                return saved;
            } catch (DataIntegrityViolationException ex) {
                log.warn("Concurrent insert took code={} attempt={}/{}", code, attempt, maxAttempts);
            }
        }
        log.error("Code generation exhausted after {} attempts", maxAttempts);
        throw new CodeSpaceExhaustedException(maxAttempts);
    }

    @Transactional(readOnly = true)
    public ShortLink resolve(String code) {
        return repository.findByCode(code).orElseThrow(() -> {
            log.info("Short link not found code={}", code);
            return new LinkNotFoundException(code);
        });
    }

    @Transactional(readOnly = true)
    public Page<ShortLink> list(Pageable pageable) {
        return repository.findAll(pageable);
    }

    private ShortLink createWithAlias(URI target, String alias) {
        if (repository.existsByCode(alias)) {
            log.warn("Rejected alias, already in use alias={}", alias);
            throw new AliasAlreadyExistsException(alias);
        }
        try {
            ShortLink saved = repository.saveAndFlush(newLink(alias, target));
            log.info("Created short link id={} alias={} host={}", saved.getId(), alias, target.getHost());
            return saved;
        } catch (DataIntegrityViolationException ex) {
            log.warn("Rejected alias, taken concurrently alias={}", alias);
            throw new AliasAlreadyExistsException(alias);
        }
    }

    private ShortLink newLink(String code, URI target) {
        return ShortLink.builder()
                .code(code)
                .targetUrl(target.toString())
                .createdAt(clock.instant())
                .build();
    }

    private URI parseAndCheck(String targetUrl) {
        if (targetUrl == null || targetUrl.isBlank()) {
            throw new InvalidTargetUrlException("targetUrl is required");
        }
        String trimmed = targetUrl.trim();
        if (trimmed.length() > MAX_URL_LENGTH) {
            throw new InvalidTargetUrlException("targetUrl exceeds " + MAX_URL_LENGTH + " characters");
        }
        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException ex) {
            throw new InvalidTargetUrlException("targetUrl is not a valid URI");
        }
        for (TargetUrlPolicy policy : policies) {
            policy.check(uri);
        }
        return uri;
    }
}
