package com.salian.shortener.analytics;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Turns a client address into a pseudonymous visitor id: SHA-256(salt | UTC day | address).
 * Including the day means the same visitor cannot be tracked across days, and the raw address is
 * never stored (privacy policy rule {@code pii-raw-ip}).
 */
@Component
public class VisitorHasher {

    private static final Logger log = LoggerFactory.getLogger(VisitorHasher.class);

    private final byte[] salt;

    @Autowired
    public VisitorHasher(AnalyticsProperties properties) {
        this(resolveSalt(properties.visitorSalt()));
    }

    VisitorHasher(String salt) {
        this.salt = salt.getBytes(StandardCharsets.UTF_8);
    }

    public String hash(String clientAddress, LocalDate day) {
        MessageDigest digest = sha256();
        digest.update(salt);
        digest.update((byte) '|');
        digest.update(day.toString().getBytes(StandardCharsets.UTF_8));
        digest.update((byte) '|');
        digest.update(String.valueOf(clientAddress).getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String resolveSalt(String configured) {
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        log.warn("shortener.analytics.visitor-salt not set; using a random per-process salt "
                + "(unique-visitor counts will not be stable across restarts)");
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        return HexFormat.of().formatHex(random);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
