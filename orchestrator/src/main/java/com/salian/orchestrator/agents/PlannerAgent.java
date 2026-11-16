package com.salian.orchestrator.agents;

import com.fasterxml.jackson.databind.JsonNode;
import com.salian.orchestrator.engine.Agent;
import com.salian.orchestrator.engine.ExecutionMode;
import com.salian.orchestrator.engine.FailureKind;
import com.salian.orchestrator.engine.StageContext;
import com.salian.orchestrator.engine.StageResult;
import com.salian.orchestrator.scenario.Playbook;
import com.salian.orchestrator.scenario.PlaybookRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Decomposes the normalized requirement into a task graph: per feature a design → implement → test
 * chain (implement and test run in parallel after design), plus cross-cutting docs, verification and
 * release tasks with explicit dependencies. Deterministic (REAL).
 */
public final class PlannerAgent implements Agent {

    private final PlaybookRepository playbooks;

    public PlannerAgent(PlaybookRepository playbooks) {
        this.playbooks = playbooks;
    }

    @Override
    public String name() {
        return "planner";
    }

    @Override
    public ExecutionMode mode() {
        return ExecutionMode.REAL;
    }

    @Override
    public StageResult execute(StageContext ctx) {
        Playbook playbook = Scenarios.playbook(playbooks, ctx);
        JsonNode requirement = ctx.input("requirement");
        List<String> featureIds = new ArrayList<>();
        requirement.path("features").forEach(f -> featureIds.add(f.path("id").asText()));
        if (featureIds.isEmpty()) {
            return StageResult.fail(FailureKind.PERMANENT,
                    "Nothing to plan: the requirement names no capability (clarification required)");
        }

        List<Map<String, Object>> tasks = new ArrayList<>();
        List<String> verifyDeps = new ArrayList<>();
        List<String> files = new ArrayList<>();
        for (String id : featureIds) {
            Playbook.Feature feature = playbook.feature(id);
            List<String> main = feature.main().stream().map(Playbook.Overlay::path).toList();
            List<String> tests = feature.tests().stream().map(Playbook.Overlay::path).toList();
            files.addAll(main);
            files.addAll(tests);
            tasks.add(task("design:" + id, "Design API/schema for " + feature.title(), "design", List.of(), List.of()));
            tasks.add(task("implement:" + id, "Implement " + feature.title(), "implement", List.of("design:" + id), main));
            tasks.add(task("test:" + id, "Write tests for " + feature.title(), "test", List.of("design:" + id), tests));
            verifyDeps.add("implement:" + id);
            verifyDeps.add("test:" + id);
        }
        List<String> designTasks = featureIds.stream().map(id -> "design:" + id).toList();
        tasks.add(task("docs", "Write change notes and usage docs", "docs", designTasks, List.of()));
        tasks.add(task("verify:build", "Build and run the full test suite", "verify", verifyDeps, List.of()));
        tasks.add(task("verify:policy", "Policy scan (security, privacy, change control)", "verify", verifyDeps, List.of()));
        tasks.add(task("verify:contract", "API contract compatibility check",
                "verify", featureIds.stream().map(id -> "implement:" + id).toList(), List.of()));
        tasks.add(task("release", "Release readiness review",
                "release", List.of("verify:build", "verify:policy", "verify:contract", "docs"), List.of()));

        List<String> risks = new ArrayList<>();
        if (files.stream().anyMatch(f -> f.contains("/db/migration/"))) {
            risks.add("Schema change: requires change-control approval and append-only migration");
        }
        if (!playbook.baseline().isEmpty()) {
            risks.add("Brownfield change: regressions in existing endpoints/tests must be caught by verification");
        }
        if (requirement.path("outOfScope").size() > 0) {
            risks.add("Explicitly deferred scope: " + requirement.path("outOfScope"));
        }

        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("summary", featureIds.size() + " feature(s), " + tasks.size() + " tasks, " + files.size() + " files: " + featureIds);
        plan.put("features", featureIds);
        plan.put("tasks", tasks);
        plan.put("parallelism", List.of(
                "implement:* and test:* run in parallel after design",
                "docs runs in parallel with implementation",
                "verify:build, verify:policy and verify:contract run in parallel and join before release"));
        plan.put("files", files);
        plan.put("risks", risks);
        ctx.output("plan", plan);
        ctx.decide("Plan " + featureIds.size() + " feature(s) as independent design→implement/test chains",
                "Feature chains share no files, so implementation and tests can proceed in parallel; "
                        + "verification is a join point because build, policy and contract checks need all code",
                List.of("Single sequential chain (rejected: slower, hides independence)",
                        "Per-file tasks (rejected: too fine-grained to review)"));
        return StageResult.ok((String) plan.get("summary"));
    }

    private static Map<String, Object> task(String id, String title, String type, List<String> dependsOn, List<String> files) {
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("id", id);
        task.put("title", title);
        task.put("type", type);
        task.put("dependsOn", dependsOn);
        task.put("files", files);
        return task;
    }
}
