package com.salian.orchestrator.audit;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

/**
 * One append-only audit record. {@code hash = sha256(prevHash + canonical(event without hash))}, so
 * editing, deleting or reordering any line breaks the chain (see {@link AuditLog#verify()}).
 *
 * @param actor {@code system:orchestrator}, {@code agent:<name>}, {@code human:<name>} or {@code delegated:<name>}
 */
public record AuditEvent(
        long seq,
        String eventId,
        String runId,
        String type,
        String stageId,
        Integer attempt,
        String actor,
        Instant timestamp,
        JsonNode data,
        String prevHash,
        String hash) {

    public AuditEvent withHash(String newHash) {
        return new AuditEvent(seq, eventId, runId, type, stageId, attempt, actor, timestamp, data, prevHash, newHash);
    }

    public AuditEvent withoutHash() {
        return withHash(null);
    }
}
