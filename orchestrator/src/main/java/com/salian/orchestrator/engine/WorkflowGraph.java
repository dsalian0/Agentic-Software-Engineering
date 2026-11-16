package com.salian.orchestrator.engine;

import com.salian.orchestrator.model.StageDefinition;
import com.salian.orchestrator.model.StageType;
import com.salian.orchestrator.model.WorkflowDefinition;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validated, immutable view of a workflow as a DAG. Rejects unknown dependencies, cycles, agent
 * stages without an agent, and rework targets that are not upstream of the failing stage.
 */
public final class WorkflowGraph {

    private final WorkflowDefinition definition;
    private final Map<String, StageDefinition> stages = new LinkedHashMap<>();
    private final Map<String, List<String>> dependents = new HashMap<>();
    private final List<String> topologicalOrder;

    public WorkflowGraph(WorkflowDefinition definition) {
        this.definition = definition;
        for (StageDefinition stage : definition.stages()) {
            if (stages.put(stage.id(), stage) != null) {
                throw new IllegalArgumentException("Duplicate stage id: " + stage.id());
            }
            dependents.put(stage.id(), new ArrayList<>());
        }
        for (StageDefinition stage : definition.stages()) {
            if (stage.type() != StageType.APPROVAL && (stage.agent() == null || stage.agent().isBlank())) {
                throw new IllegalArgumentException("Stage '" + stage.id() + "' needs an agent");
            }
            for (String dep : stage.dependsOn()) {
                if (!stages.containsKey(dep)) {
                    throw new IllegalArgumentException("Stage '" + stage.id() + "' depends on unknown stage '" + dep + "'");
                }
                dependents.get(dep).add(stage.id());
            }
        }
        this.topologicalOrder = sort();
        for (StageDefinition stage : definition.stages()) {
            if (stage.onFailure() != null) {
                String target = stage.onFailure().stage();
                if (!stages.containsKey(target)) {
                    throw new IllegalArgumentException("Stage '" + stage.id() + "' reworks unknown stage '" + target + "'");
                }
                if (!downstreamClosure(Set.of(target)).contains(stage.id())) {
                    throw new IllegalArgumentException("Rework target '" + target + "' must be upstream of '" + stage.id() + "'");
                }
            }
        }
    }

    public WorkflowDefinition definition() {
        return definition;
    }

    public StageDefinition stage(String id) {
        StageDefinition stage = stages.get(id);
        if (stage == null) {
            throw new IllegalArgumentException("Unknown stage: " + id);
        }
        return stage;
    }

    public List<String> topologicalOrder() {
        return topologicalOrder;
    }

    /** The given stages plus everything transitively downstream of them, in topological order. */
    public List<String> downstreamClosure(Set<String> roots) {
        Set<String> seen = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>(roots);
        while (!queue.isEmpty()) {
            String id = queue.poll();
            if (seen.add(id)) {
                queue.addAll(dependents.getOrDefault(id, List.of()));
            }
        }
        return topologicalOrder.stream().filter(seen::contains).toList();
    }

    /** Stages that declare {@code artifactKey} as an input. */
    public List<String> readersOf(String artifactKey) {
        return topologicalOrder.stream()
                .filter(id -> stages.get(id).readKeys().contains(artifactKey))
                .toList();
    }

    /** Stages that may run concurrently: grouped by dependency depth (longest path from a root). */
    public List<List<String>> parallelLevels() {
        Map<String, Integer> depth = new HashMap<>();
        for (String id : topologicalOrder) {
            int d = stages.get(id).dependsOn().stream().mapToInt(dep -> depth.get(dep) + 1).max().orElse(0);
            depth.put(id, d);
        }
        int max = depth.values().stream().mapToInt(Integer::intValue).max().orElse(-1);
        List<List<String>> levels = new ArrayList<>();
        for (int i = 0; i <= max; i++) {
            int level = i;
            levels.add(topologicalOrder.stream().filter(id -> depth.get(id) == level).toList());
        }
        return levels;
    }

    private List<String> sort() {
        Map<String, Integer> inDegree = new LinkedHashMap<>();
        stages.values().forEach(s -> inDegree.put(s.id(), s.dependsOn().size()));
        Deque<String> ready = new ArrayDeque<>();
        inDegree.forEach((id, degree) -> {
            if (degree == 0) {
                ready.add(id);
            }
        });
        List<String> order = new ArrayList<>();
        while (!ready.isEmpty()) {
            String id = ready.poll();
            order.add(id);
            for (String next : dependents.get(id)) {
                if (inDegree.merge(next, -1, Integer::sum) == 0) {
                    ready.add(next);
                }
            }
        }
        if (order.size() != stages.size()) {
            List<String> cyclic = stages.keySet().stream().filter(id -> !order.contains(id)).toList();
            throw new IllegalArgumentException("Workflow has a dependency cycle involving " + cyclic);
        }
        return List.copyOf(order);
    }
}
