package com.salian.orchestrator.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Complete persisted state of one workflow run (serialized to runs/&lt;id&gt;/state.json after every transition). */
@Data
@NoArgsConstructor
public class RunState {

    private String runId;
    private String scenario;
    private String workflowId;
    private String workflowVersion;
    private RunStatus status = RunStatus.RUNNING;
    private String statusReason;
    private Instant createdAt;
    private Instant updatedAt;
    private Map<String, String> parameters = new LinkedHashMap<>();
    private Map<String, StageState> stages = new LinkedHashMap<>();
    private Map<String, List<ArtifactVersion>> artifacts = new LinkedHashMap<>();
    private Map<String, ApprovalRecord> approvals = new LinkedHashMap<>();
    /** Human answers per clarification stage. */
    private Map<String, JsonNode> clarifications = new LinkedHashMap<>();
    /** When each clarification stage first asked its questions (for timeout handling). */
    private Map<String, Instant> clarificationRequests = new LinkedHashMap<>();
    private List<Decision> decisions = new ArrayList<>();
    private Map<String, Integer> reworkCounts = new LinkedHashMap<>();
    private int stageExecutions;
    private int replanCount;

    @JsonIgnore
    public Optional<ArtifactVersion> latest(String key) {
        List<ArtifactVersion> versions = artifacts.getOrDefault(key, List.of());
        for (int i = versions.size() - 1; i >= 0; i--) {
            if (!versions.get(i).isStale()) {
                return Optional.of(versions.get(i));
            }
        }
        return Optional.empty();
    }

    @JsonIgnore
    public int nextVersion(String key) {
        return artifacts.getOrDefault(key, List.of()).size() + 1;
    }

    @JsonIgnore
    public StageState stage(String id) {
        StageState state = stages.get(id);
        if (state == null) {
            throw new IllegalArgumentException("Unknown stage: " + id);
        }
        return state;
    }
}
