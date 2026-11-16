package com.salian.orchestrator.agents;

import com.fasterxml.jackson.databind.JsonNode;
import com.salian.orchestrator.engine.Agent;
import com.salian.orchestrator.engine.ExecutionMode;
import com.salian.orchestrator.engine.StageContext;
import com.salian.orchestrator.engine.StageResult;
import com.salian.orchestrator.scenario.Playbook;
import com.salian.orchestrator.scenario.PlaybookRepository;
import com.salian.orchestrator.support.CodebaseScanner;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Derives the design contract of the planned change (REAL analysis of the planned artifacts): API
 * endpoints, schema migrations, new dependencies and configuration properties, and decides whether
 * the change needs change-control approval (schema or dependency changes, or edits to existing code).
 */
public final class DesignerAgent implements Agent {

    private static final Pattern DEPENDENCY = Pattern.compile(
            "<dependency>\\s*<groupId>([^<]+)</groupId>\\s*<artifactId>([^<]+)</artifactId>");
    private static final Pattern CONFIG_PREFIX = Pattern.compile("@ConfigurationProperties\\(prefix\\s*=\\s*\"([^\"]+)\"");

    private final PlaybookRepository playbooks;

    public DesignerAgent(PlaybookRepository playbooks) {
        this.playbooks = playbooks;
    }

    @Override
    public String name() {
        return "designer";
    }

    @Override
    public ExecutionMode mode() {
        return ExecutionMode.REAL;
    }

    @Override
    public StageResult execute(StageContext ctx) {
        Playbook playbook = Scenarios.playbook(playbooks, ctx);
        JsonNode plan = ctx.input("plan");
        Optional<JsonNode> impact = ctx.optionalInput("impact-report");
        Map<String, String> planned = ImpactAnalyzerAgent.plannedContent(playbook, plan, playbooks);

        List<String> endpoints = new ArrayList<>();
        List<String> migrations = new ArrayList<>();
        Set<String> configPrefixes = new LinkedHashSet<>();
        Set<String> dependencies = new LinkedHashSet<>();
        planned.forEach((path, content) -> {
            if (path.endsWith(".java") && path.contains("/src/main/")) {
                endpoints.addAll(CodebaseScanner.analyze(path, content).endpoints());
                Matcher m = CONFIG_PREFIX.matcher(content);
                while (m.find()) {
                    configPrefixes.add(m.group(1));
                }
            }
            if (path.contains("/db/migration/")) {
                migrations.add(path.substring(path.lastIndexOf('/') + 1));
            }
            if (path.endsWith("pom.xml")) {
                Matcher m = DEPENDENCY.matcher(content);
                while (m.find()) {
                    dependencies.add(m.group(1).trim() + ":" + m.group(2).trim());
                }
            }
        });
        Set<String> baselineDeps = new LinkedHashSet<>();
        ctx.workspace().read(Scenarios.PROJECT + "/pom.xml").ifPresent(pom -> {
            Matcher m = DEPENDENCY.matcher(pom);
            while (m.find()) {
                baselineDeps.add(m.group(1).trim() + ":" + m.group(2).trim());
            }
        });
        List<String> newDependencies = dependencies.stream().filter(d -> !baselineDeps.contains(d)).toList();
        int modifiedExisting = impact.map(i -> i.path("modifiedFiles").size()).orElse(0);

        List<String> reasons = new ArrayList<>();
        if (!migrations.isEmpty()) {
            reasons.add("schema migration(s) " + migrations);
        }
        if (!newDependencies.isEmpty()) {
            reasons.add("new dependencies " + newDependencies);
        }
        if (modifiedExisting > 0) {
            reasons.add(modifiedExisting + " existing file(s) modified");
        }
        boolean changeControl = !migrations.isEmpty() || !newDependencies.isEmpty();

        Map<String, Object> design = new LinkedHashMap<>();
        design.put("summary", endpoints.size() + " endpoint(s), " + migrations.size() + " migration(s), "
                + newDependencies.size() + " new dependency(ies); change control "
                + (changeControl ? "REQUIRED (" + String.join(", ", reasons) + ")" : "not required"));
        design.put("endpoints", endpoints);
        design.put("migrations", migrations);
        design.put("newDependencies", newDependencies);
        design.put("configurationPrefixes", configPrefixes);
        design.put("requiresChangeControl", changeControl);
        design.put("changeControlReasons", reasons);
        design.put("errorModel", "RFC 7807 problem details (400 validation, 404 unknown code, 409 alias conflict, 429 rate limit, 503 code space)");
        ctx.output("design", design);
        ctx.decide(changeControl ? "Route through change-control approval" : "No change-control gate needed",
                changeControl ? "Policy: schema and dependency changes are high-impact (" + String.join(", ", reasons) + ")"
                        : "No schema or dependency changes; code-only changes are covered by verification gates",
                List.of());
        return StageResult.ok((String) design.get("summary"));
    }
}
