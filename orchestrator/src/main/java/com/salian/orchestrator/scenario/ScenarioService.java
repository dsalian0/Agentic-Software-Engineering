package com.salian.orchestrator.scenario;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.salian.orchestrator.agents.Scenarios;
import com.salian.orchestrator.engine.Orchestrator;
import com.salian.orchestrator.model.RunState;
import com.salian.orchestrator.support.Json;
import com.salian.orchestrator.workspace.Workspace;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Starts scenario runs (seeding brownfield baselines) and builds scenario-defined human inputs. */
public final class ScenarioService {

    private static final DateTimeFormatter RUN_ID_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final Orchestrator orchestrator;
    private final PlaybookRepository playbooks;
    private final Clock clock;

    public ScenarioService(Orchestrator orchestrator, PlaybookRepository playbooks, Clock clock) {
        this.orchestrator = orchestrator;
        this.playbooks = playbooks;
        this.clock = clock;
    }

    public PlaybookRepository playbooks() {
        return playbooks;
    }

    public Orchestrator orchestrator() {
        return orchestrator;
    }

    public String newRunId(String scenario) {
        return scenario + "-" + RUN_ID_TIME.format(clock.instant());
    }

    public RunState start(String scenarioId, String runId, String autoApproveAs, boolean mavenOffline) {
        Playbook playbook = playbooks.load(scenarioId);
        Workspace workspace = orchestrator.workspace(runId);
        int seeded = 0;
        for (String base : playbook.baseline()) {
            for (Map.Entry<String, String> file : playbooks.finalFiles(base).entrySet()) {
                workspace.seed(file.getKey(), file.getValue());
                seeded++;
            }
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put(Scenarios.PARAM_SCENARIO, scenarioId);
        params.put(Scenarios.PARAM_BASELINE, String.valueOf(!playbook.baseline().isEmpty()));
        params.put("baselineFiles", String.valueOf(seeded));
        params.put(Scenarios.PARAM_MAVEN_OFFLINE, String.valueOf(mavenOffline));
        if (autoApproveAs != null && !autoApproveAs.isBlank()) {
            params.put(Orchestrator.PARAM_AUTO_APPROVE, autoApproveAs);
        }
        return orchestrator.start(runId, scenarioId, params, playbooks.faults(scenarioId));
    }

    public RunState resume(String runId) {
        RunState state = orchestrator.store().load(runId);
        return orchestrator.resume(runId, playbooks.faults(state.getScenario()));
    }

    /** The scenario's scripted clarification answers. */
    public JsonNode clarificationAnswers(String scenarioId) {
        return Json.tree(playbooks.load(scenarioId).clarificationAnswers());
    }

    /** Builds the amended requirement the scenario's product owner submits (adds features + text). */
    public JsonNode amendedRequirement(String runId) {
        RunState state = orchestrator.store().load(runId);
        Playbook playbook = playbooks.load(state.getScenario());
        Playbook.Amendment amendment = playbook.amendment();
        if (amendment == null) {
            throw new IllegalStateException("Scenario " + playbook.id() + " defines no amendment");
        }
        ObjectNode requirement = state.latest("requirement").orElseThrow().getContent().deepCopy();
        requirement.put("rawText", requirement.path("rawText").asText() + "\n" + amendment.text());
        ArrayNode features = (ArrayNode) requirement.withArray("features");
        ArrayNode acceptance = (ArrayNode) requirement.withArray("acceptanceCriteria");
        for (String id : amendment.addFeatures()) {
            Playbook.Feature feature = playbook.feature(id);
            features.add(Json.tree(Map.of("id", feature.id(), "title", feature.title(), "matchedOn", List.of("amendment"))));
            feature.acceptance().forEach(acceptance::add);
        }
        requirement.withArray("amendments").add(amendment.note());
        StringBuilder titles = new StringBuilder();
        features.forEach(f -> titles.append(titles.isEmpty() ? "" : "; ").append(f.path("title").asText()));
        requirement.put("summary", requirement.path("kind").asText() + " (amended): " + titles);
        return requirement;
    }

    public String amendmentNote(String runId) {
        RunState state = orchestrator.store().load(runId);
        return playbooks.load(state.getScenario()).amendment().note();
    }
}
