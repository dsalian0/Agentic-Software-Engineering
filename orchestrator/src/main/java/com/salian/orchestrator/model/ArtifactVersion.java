package com.salian.orchestrator.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Immutable-by-convention version of a shared-state artifact. {@code derivedFrom} lists the exact
 * input versions ({@code key@version}) the producer saw, which is the decision-lineage chain.
 */
@Data
@NoArgsConstructor
public class ArtifactVersion {

    private String key;
    private int version;
    private String contentHash;
    private String producedBy;
    private int attempt;
    private List<String> derivedFrom = new ArrayList<>();
    private Instant createdAt;
    /** Set when the producing stage was invalidated by rework/replanning; kept for lineage. */
    private boolean stale;
    private JsonNode content;

    public String ref() {
        return key + "@" + version;
    }
}
