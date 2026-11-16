package com.salian.shortener.link;

import com.salian.shortener.config.ShortenerProperties;
import java.security.SecureRandom;
import java.util.random.RandomGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Generates random Base62 codes. Random (not sequential) codes prevent enumeration of
 * other users' links; uniqueness is enforced by the database constraint, not by this class.
 */
@Component
public class CodeGenerator {

    static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    private final RandomGenerator random;
    private final int length;

    @Autowired
    public CodeGenerator(ShortenerProperties properties) {
        this(new SecureRandom(), properties.codeLength());
    }

    CodeGenerator(RandomGenerator random, int length) {
        if (length < 1) {
            throw new IllegalArgumentException("length must be positive");
        }
        this.random = random;
        this.length = length;
    }

    public String next() {
        StringBuilder code = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return code.toString();
    }
}
