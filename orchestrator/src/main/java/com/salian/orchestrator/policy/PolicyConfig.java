package com.salian.orchestrator.policy;

import com.salian.orchestrator.support.Json;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Policy-as-code loaded from {@code policies/policy.yaml}: which rules are active and how severe a
 * violation is. Rule logic lives in {@link PolicyEngine}; configuration only tunes it.
 */
public record PolicyConfig(
        String version,
        List<String> allowedPathPrefixes,
        List<String> dependencyAllowlist,
        int maxChangedFiles,
        Map<String, Severity> rules) {

    public enum Severity { OFF, WARN, BLOCK }

    public PolicyConfig {
        allowedPathPrefixes = allowedPathPrefixes == null ? List.of() : List.copyOf(allowedPathPrefixes);
        dependencyAllowlist = dependencyAllowlist == null ? List.of() : List.copyOf(dependencyAllowlist);
        rules = rules == null ? Map.of() : Map.copyOf(rules);
        maxChangedFiles = maxChangedFiles <= 0 ? 80 : maxChangedFiles;
    }

    public Severity severity(String rule) {
        return rules.getOrDefault(rule, Severity.OFF);
    }

    public static PolicyConfig load(Path file) {
        try {
            return Json.yaml().readValue(file.toFile(), PolicyConfig.class);
        } catch (IOException ex) {
            throw new UncheckedIOException("Cannot load policy " + file, ex);
        }
    }
}
