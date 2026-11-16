package com.salian.orchestrator.model;

import java.util.List;

/**
 * One node of the workflow dependency graph.
 *
 * @param dependsOn     upstream stages; this stage is ready when all of them SUCCEEDED or were SKIPPED (join = ALL)
 * @param reads         artifact keys consumed (entry gate: must exist); suffix {@code ?} marks optional inputs.
 *                      Reads also define replanning: when a read artifact changes, this stage is invalidated.
 * @param writes        artifact keys this stage must produce (exit gate {@code outputs-present})
 * @param when          optional condition ({@code artifact.path}, {@code !artifact.path} or {@code param:name});
 *                      false = stage SKIPPED, which makes the path through the graph data-dependent
 * @param exitGates     named gates evaluated after the agent succeeds
 * @param fallbackAgent alternate strategy tried once after the primary agent fails terminally
 * @param timeoutMs     for APPROVAL/CLARIFICATION stages: how long to wait for a human before safe-stopping
 */
public record StageDefinition(
        String id,
        StageType type,
        String description,
        String agent,
        String fallbackAgent,
        List<String> dependsOn,
        List<String> reads,
        List<String> writes,
        String when,
        List<String> exitGates,
        RetryPolicy retry,
        ReworkPolicy onFailure,
        Long timeoutMs) {

    public StageDefinition {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("stage id is required");
        }
        type = type == null ? StageType.AGENT : type;
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
        reads = reads == null ? List.of() : List.copyOf(reads);
        writes = writes == null ? List.of() : List.copyOf(writes);
        exitGates = exitGates == null ? List.of() : List.copyOf(exitGates);
        retry = retry == null ? RetryPolicy.NONE : retry;
    }

    /** Artifact keys read, with the optional marker stripped. */
    public List<String> readKeys() {
        return reads.stream().map(StageDefinition::stripOptional).toList();
    }

    public List<String> requiredReads() {
        return reads.stream().filter(r -> !r.endsWith("?")).toList();
    }

    public static String stripOptional(String key) {
        return key.endsWith("?") ? key.substring(0, key.length() - 1) : key;
    }
}
