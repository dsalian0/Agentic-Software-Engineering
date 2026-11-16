package com.salian.orchestrator.agents;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.salian.orchestrator.engine.Agent;
import com.salian.orchestrator.engine.ExecutionMode;
import com.salian.orchestrator.engine.FailureKind;
import com.salian.orchestrator.engine.StageContext;
import com.salian.orchestrator.engine.StageResult;
import com.salian.orchestrator.scenario.Playbook;
import com.salian.orchestrator.scenario.PlaybookRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Applies human answers to the draft requirement: chosen options become features (with their
 * acceptance criteria), out-of-scope choices are recorded explicitly. Rejects incomplete answers
 * rather than guessing.
 */
public final class ClarificationApplierAgent implements Agent {

    private final PlaybookRepository playbooks;

    public ClarificationApplierAgent(PlaybookRepository playbooks) {
        this.playbooks = playbooks;
    }

    @Override
    public String name() {
        return "clarification-applier";
    }

    @Override
    public ExecutionMode mode() {
        return ExecutionMode.REAL;
    }

    @Override
    public StageResult execute(StageContext ctx) {
        Playbook playbook = Scenarios.playbook(playbooks, ctx);
        JsonNode report = ctx.input("ambiguity-report");
        JsonNode answers = ctx.clarificationAnswers().orElseThrow();
        ObjectNode requirement = report.path("draftRequirement").deepCopy();
        ArrayNode features = (ArrayNode) requirement.withArray("features");
        ArrayNode acceptance = (ArrayNode) requirement.withArray("acceptanceCriteria");
        ArrayNode outOfScope = (ArrayNode) requirement.putArray("outOfScope");
        ArrayNode resolved = requirement.putArray("resolvedAmbiguities");

        List<String> unanswered = new ArrayList<>();
        for (JsonNode question : report.path("questions")) {
            String id = question.path("id").asText();
            JsonNode answer = answers.path(id);
            if (answer.isMissingNode() || answer.asText().isBlank()) {
                unanswered.add(id);
                continue;
            }
            String choice = answer.asText();
            JsonNode option = null;
            for (JsonNode o : question.path("options")) {
                if (o.path("id").asText().equals(choice)) {
                    option = o;
                }
            }
            resolved.add(com.salian.orchestrator.support.Json.tree(
                    Map.of("term", id, "answer", choice, "label", option == null ? choice : option.path("label").asText())));
            if (option == null) {
                // Free-text answer: keep it as an explicit acceptance criterion.
                acceptance.add("[" + id + "] " + choice);
            } else if (option.path("outOfScope").asBoolean()) {
                outOfScope.add(option.path("label").asText());
            } else if (playbook.features().stream().anyMatch(f -> f.id().equals(choice))) {
                Playbook.Feature feature = playbook.feature(choice);
                features.add(com.salian.orchestrator.support.Json.tree(
                        Map.of("id", feature.id(), "title", feature.title(), "matchedOn", List.of("clarification:" + id))));
                feature.acceptance().forEach(acceptance::add);
            }
        }
        if (!unanswered.isEmpty()) {
            return StageResult.fail(FailureKind.PERMANENT, "Unanswered clarification questions: " + unanswered);
        }
        List<String> titles = new ArrayList<>();
        features.forEach(f -> titles.add(f.path("title").asText()));
        requirement.put("summary", features.isEmpty()
                ? "Clarified: no new capability required"
                : requirement.path("kind").asText() + " (clarified): " + String.join("; ", titles));
        ctx.output("requirement", requirement);
        ctx.decide("Normalized requirement from clarification answers",
                "Each vague term was mapped to an explicit capability or recorded as out of scope; "
                        + features.size() + " feature(s), " + outOfScope.size() + " out-of-scope item(s)",
                List.of("Proceed with defaults for unanswered terms (not allowed: answers required)"));
        return StageResult.ok(requirement.path("summary").asText());
    }
}
