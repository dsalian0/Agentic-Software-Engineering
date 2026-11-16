package com.salian.orchestrator.agents;

import com.fasterxml.jackson.databind.JsonNode;
import com.salian.orchestrator.engine.Agent;
import com.salian.orchestrator.engine.ExecutionMode;
import com.salian.orchestrator.engine.StageContext;
import com.salian.orchestrator.engine.StageResult;
import com.salian.orchestrator.scenario.Playbook;
import com.salian.orchestrator.scenario.PlaybookRepository;
import com.salian.orchestrator.support.CodebaseScanner;
import com.salian.orchestrator.support.CodebaseScanner.JavaClass;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Brownfield codebase reasoning (REAL static analysis). Scans the existing code in the sandbox,
 * classifies every planned file as added or modified, and derives impacted packages, endpoints,
 * data stores, event-driven data flows and regression risks. Also records the baseline the
 * verification gates compare against (test count, endpoints, migrations).
 */
public final class ImpactAnalyzerAgent implements Agent {

    private final PlaybookRepository playbooks;

    public ImpactAnalyzerAgent(PlaybookRepository playbooks) {
        this.playbooks = playbooks;
    }

    @Override
    public String name() {
        return "impact-analyzer";
    }

    @Override
    public ExecutionMode mode() {
        return ExecutionMode.REAL;
    }

    @Override
    public StageResult execute(StageContext ctx) {
        Playbook playbook = Scenarios.playbook(playbooks, ctx);
        JsonNode plan = ctx.input("plan");
        CodebaseScanner.Model baseline = CodebaseScanner.scan(ctx.workspace(), Scenarios.PROJECT);

        List<String> added = new ArrayList<>();
        List<String> modified = new ArrayList<>();
        List<String> unchanged = new ArrayList<>();
        Map<String, String> planned = plannedContent(playbook, plan);
        planned.forEach((path, content) -> {
            Optional<String> existing = ctx.workspace().read(path);
            if (existing.isEmpty()) {
                added.add(path);
            } else if (!existing.get().equals(content)) {
                modified.add(path);
            } else {
                unchanged.add(path);
            }
        });

        Set<String> impactedPackages = new LinkedHashSet<>();
        List<String> impactedEndpoints = new ArrayList<>();
        List<String> newEndpoints = new ArrayList<>();
        List<String> newTables = new ArrayList<>();
        List<String> dataFlows = new ArrayList<>();
        List<String> risks = new ArrayList<>();
        Map<String, JavaClass> plannedClasses = new LinkedHashMap<>();
        planned.forEach((path, content) -> {
            if (path.endsWith(".java") && path.contains("/src/main/")) {
                plannedClasses.put(path, CodebaseScanner.analyze(path, content));
            }
        });
        for (String path : modified) {
            JavaClass before = baseline.classes().stream().filter(c -> c.path().equals(path)).findFirst().orElse(null);
            if (before != null) {
                impactedPackages.add(before.packageName());
                impactedEndpoints.addAll(before.endpoints());
                risks.add("Modifies existing " + before.name() + " " + before.stereotypes()
                        + (before.endpoints().isEmpty() ? "" : " serving " + before.endpoints()));
            }
            if (path.contains("/db/migration/")) {
                risks.add("Existing migration " + path + " would change (violates append-only rule)");
            }
        }
        for (String path : added) {
            JavaClass c = plannedClasses.get(path);
            if (c != null) {
                impactedPackages.add(c.packageName());
                newEndpoints.addAll(c.endpoints());
                if (c.table() != null) {
                    newTables.add(c.table());
                }
            }
        }
        // Event-driven data flows: who publishes events that new/changed listeners consume.
        for (JavaClass consumer : plannedClasses.values()) {
            for (String event : consumer.listens()) {
                List<String> publishers = new ArrayList<>();
                baseline.classes().stream().filter(c -> c.publishes().contains(event)).forEach(c -> publishers.add(c.name()));
                plannedClasses.values().stream().filter(c -> c.publishes().contains(event) && !publishers.contains(c.name()))
                        .forEach(c -> publishers.add(c.name()));
                String sink = plannedClasses.values().stream().filter(c -> c.table() != null).map(JavaClass::table)
                        .findFirst().map(t -> " -> table " + t).orElse("");
                dataFlows.add(String.join("/", publishers) + " -> " + event + " -> " + consumer.name() + sink);
                if (!publishers.isEmpty()) {
                    risks.add("New consumer " + consumer.name() + " runs on the publisher's thread (" + publishers
                            + "): it must be non-blocking so redirect latency is unaffected");
                }
            }
        }
        if (!newTables.isEmpty()) {
            risks.add("Schema change adds table(s) " + newTables + ": requires change-control approval");
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("summary", modified.size() + " existing file(s) modified, " + added.size() + " added; packages "
                + impactedPackages + "; " + dataFlows.size() + " data flow(s)");
        report.put("baseline", Map.of(
                "classes", baseline.classes().size(),
                "packages", baseline.packages(),
                "endpoints", baseline.endpoints(),
                "migrations", baseline.migrations(),
                "testMethods", baseline.testMethods()));
        report.put("modifiedFiles", modified);
        report.put("addedFiles", added);
        report.put("unchangedFiles", unchanged.size());
        report.put("impactedPackages", impactedPackages);
        report.put("impactedEndpoints", impactedEndpoints);
        report.put("newEndpoints", newEndpoints);
        report.put("newTables", newTables);
        report.put("dataFlows", dataFlows);
        report.put("regressionRisks", risks);
        ctx.output("impact-report", report);
        ctx.decide("Change surface: " + modified.size() + " modified / " + added.size() + " added file(s)",
                modified.isEmpty()
                        ? "The enhancement attaches through existing extension points without editing current code"
                        : "Edits limited to " + modified + "; everything else is additive, which keeps the regression surface small",
                List.of("Modify LinkService/RedirectController directly for analytics (rejected: couples redirect latency to persistence)"));
        return StageResult.ok((String) report.get("summary"));
    }

    static Map<String, String> plannedContent(Playbook playbook, JsonNode plan, PlaybookRepository playbooks) {
        Map<String, String> planned = new LinkedHashMap<>();
        for (JsonNode f : plan.path("features")) {
            Playbook.Feature feature = playbook.feature(f.asText());
            feature.main().forEach(o -> planned.put(o.path(), playbooks.content(playbook.id(), o)));
            feature.tests().forEach(o -> planned.put(o.path(), playbooks.content(playbook.id(), o)));
        }
        return planned;
    }

    private Map<String, String> plannedContent(Playbook playbook, JsonNode plan) {
        return plannedContent(playbook, plan, playbooks);
    }
}
