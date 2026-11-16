package com.salian.orchestrator.support;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Shared Jackson configuration plus hashing helpers used for content hashes and the audit chain. */
public final class Json {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    /** Sorted keys: used only for hashing, so equal content always yields the same hash. */
    private static final ObjectMapper CANONICAL = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    private static final ObjectMapper YAML = YAMLMapper.builder(new YAMLFactory())
            .addModule(new JavaTimeModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private Json() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static ObjectMapper yaml() {
        return YAML;
    }

    public static JsonNode tree(Object value) {
        return MAPPER.valueToTree(value);
    }

    /** Compact JSON with sorted keys, so equal content always hashes equally. */
    public static String canonical(Object value) {
        try {
            JsonNode node = value instanceof JsonNode n ? n : MAPPER.valueToTree(value);
            Object sorted = MAPPER.treeToValue(node, Object.class);
            return CANONICAL.writeValueAsString(sorted);
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot serialize value", ex);
        }
    }

    public static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    public static String pretty(Object value) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
