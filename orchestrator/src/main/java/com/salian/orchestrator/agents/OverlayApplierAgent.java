package com.salian.orchestrator.agents;

import com.fasterxml.jackson.databind.JsonNode;
import com.salian.orchestrator.engine.Agent;
import com.salian.orchestrator.engine.ExecutionMode;
import com.salian.orchestrator.engine.StageContext;
import com.salian.orchestrator.engine.StageResult;
import com.salian.orchestrator.scenario.Playbook;
import com.salian.orchestrator.scenario.PlaybookRepository;
import com.salian.orchestrator.support.Json;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * SCRIPTED code-producing agent. It does not generate code: it selects the pre-authored files for
 * the planned features from the scenario playbook and writes them into the sandbox through the
 * journaled workspace. Used as the implementer (main code) and the test writer (test code).
 *
 * <p>The implementer also applies the playbook's declared {@code injectedDefects} on its first pass
 * and drops them once policy feedback names the defect's rule; this demonstrates the guardrail and
 * rework loop deterministically and is labelled as injected everywhere it appears.
 */
public final class OverlayApplierAgent implements Agent {

    public enum Target { MAIN, TESTS }

    private final PlaybookRepository playbooks;
    private final Target target;

    public OverlayApplierAgent(PlaybookRepository playbooks, Target target) {
        this.playbooks = playbooks;
        this.target = target;
    }

    @Override
    public String name() {
        return target == Target.MAIN ? "implementer" : "test-writer";
    }

    @Override
    public ExecutionMode mode() {
        return ExecutionMode.SCRIPTED;
    }

    @Override
    public StageResult execute(StageContext ctx) {
        Playbook playbook = Scenarios.playbook(playbooks, ctx);
        JsonNode plan = ctx.input("plan");
        Optional<JsonNode> feedback = ctx.optionalInput("policy-feedback");
        List<String> remediatedRules = new ArrayList<>();
        feedback.ifPresent(f -> f.path("details").path("findings")
                .forEach(finding -> remediatedRules.add(finding.path("rule").asText())));

        List<Map<String, Object>> written = new ArrayList<>();
        List<Map<String, Object>> defects = new ArrayList<>();
        for (JsonNode featureId : plan.path("features")) {
            Playbook.Feature feature = playbook.feature(featureId.asText());
            for (Playbook.Overlay overlay : target == Target.MAIN ? feature.main() : feature.tests()) {
                String content = playbooks.content(playbook.id(), overlay);
                String source = overlay.from() == null ? "repo:" + overlay.path() : "playbook:" + overlay.from();
                Optional<Playbook.Defect> defect = target == Target.MAIN
                        ? playbook.injectedDefects().stream().filter(d -> d.path().equals(overlay.path())).findFirst()
                        : Optional.empty();
                if (defect.isPresent() && !remediatedRules.contains(defect.get().rule())) {
                    content = playbooks.defectContent(playbook.id(), defect.get());
                    source = "playbook:" + defect.get().from() + " [INJECTED DEFECT]";
                    defects.add(Map.of("path", overlay.path(), "rule", defect.get().rule(),
                            "label", defect.get().label(), "injected", true));
                }
                ctx.workspace().write(ctx.stageId(), overlay.path(), content);
                written.add(Map.of("path", overlay.path(), "source", source, "sha256", Json.sha256(content)));
            }
        }
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("summary", written.size() + " file(s) applied for features " + plan.path("features")
                + (defects.isEmpty() ? "" : " (" + defects.size() + " injected defect(s))")
                + (remediatedRules.isEmpty() ? "" : " remediating " + remediatedRules));
        changes.put("executionMode", "SCRIPTED: content selected from playbook '" + playbook.id() + "', not generated");
        changes.put("files", written);
        changes.put("injectedDefects", defects);
        changes.put("remediatedRules", remediatedRules);
        ctx.output(target == Target.MAIN ? "code-changes" : "test-changes", changes);
        if (!remediatedRules.isEmpty()) {
            ctx.decide("Remediate policy findings " + remediatedRules,
                    "Policy feedback from the previous execution blocked release; applying the compliant variant",
                    List.of("Request a policy waiver (rejected: privacy rule is non-waivable)"));
        }
        return StageResult.ok((String) changes.get("summary"));
    }
}
