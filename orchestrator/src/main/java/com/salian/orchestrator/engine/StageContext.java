package com.salian.orchestrator.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.salian.orchestrator.audit.AuditLog;
import com.salian.orchestrator.model.ArtifactVersion;
import com.salian.orchestrator.model.StageDefinition;
import com.salian.orchestrator.support.Json;
import com.salian.orchestrator.workspace.Workspace;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Everything an agent may see or touch during one attempt. Inputs are a consistent snapshot of the
 * declared {@code reads} taken when the stage started; outputs and decisions are buffered and only
 * committed by the engine if the attempt passes its exit gates.
 */
public final class StageContext {

    private final String runId;
    private final StageDefinition stage;
    private final int execution;
    private final int attempt;
    private final boolean fallback;
    private final Map<String, ArtifactVersion> inputs;
    private final Map<String, String> parameters;
    private final JsonNode clarificationAnswers;
    private final Workspace workspace;
    private final Path runDir;
    private final AuditLog audit;
    private final String actor;
    private final Map<String, JsonNode> outputs = new LinkedHashMap<>();
    private final List<PendingDecision> decisions = new ArrayList<>();

    StageContext(String runId, StageDefinition stage, int execution, int attempt, boolean fallback,
                 Map<String, ArtifactVersion> inputs, Map<String, String> parameters, JsonNode clarificationAnswers,
                 Workspace workspace, Path runDir, AuditLog audit, String actor) {
        this.runId = runId;
        this.stage = stage;
        this.execution = execution;
        this.attempt = attempt;
        this.fallback = fallback;
        this.inputs = inputs;
        this.parameters = parameters;
        this.clarificationAnswers = clarificationAnswers;
        this.workspace = workspace;
        this.runDir = runDir;
        this.audit = audit;
        this.actor = actor;
    }

    public String runId() {
        return runId;
    }

    public StageDefinition stage() {
        return stage;
    }

    public String stageId() {
        return stage.id();
    }

    /** 1 for the first execution; incremented each time rework or replanning re-runs the stage. */
    public int execution() {
        return execution;
    }

    public int attempt() {
        return attempt;
    }

    public boolean isFallback() {
        return fallback;
    }

    public JsonNode input(String key) {
        return optionalInput(key).orElseThrow(() -> new IllegalStateException(
                "Stage '" + stage.id() + "' read undeclared or missing artifact '" + key + "'"));
    }

    public Optional<JsonNode> optionalInput(String key) {
        return Optional.ofNullable(inputs.get(key)).map(ArtifactVersion::getContent);
    }

    public Optional<ArtifactVersion> inputVersion(String key) {
        return Optional.ofNullable(inputs.get(key));
    }

    public String parameter(String name) {
        return parameters.get(name);
    }

    public Map<String, String> parameters() {
        return Collections.unmodifiableMap(parameters);
    }

    public Optional<JsonNode> clarificationAnswers() {
        return Optional.ofNullable(clarificationAnswers);
    }

    public Workspace workspace() {
        return workspace;
    }

    public Path runDir() {
        return runDir;
    }

    public void output(String key, Object content) {
        if (!stage.writes().contains(key)) {
            throw new IllegalStateException("Stage '" + stage.id() + "' may not write undeclared artifact '" + key + "'");
        }
        outputs.put(key, Json.tree(content));
    }

    public void decide(String summary, String rationale, List<String> alternatives) {
        decisions.add(new PendingDecision(summary, rationale, alternatives == null ? List.of() : List.copyOf(alternatives)));
    }

    /** Emits a domain audit event attributed to this agent (e.g. POLICY_EVALUATED). */
    public void emit(String type, Object data) {
        audit.append(type, stage.id(), attempt, actor, data);
    }

    Map<String, JsonNode> outputs() {
        return outputs;
    }

    List<PendingDecision> decisions() {
        return decisions;
    }

    List<String> inputRefs() {
        return inputs.values().stream().map(ArtifactVersion::ref).toList();
    }

    record PendingDecision(String summary, String rationale, List<String> alternatives) {
    }
}
