package com.salian.orchestrator.engine;

import static com.salian.orchestrator.engine.EngineFixture.agent;
import static com.salian.orchestrator.engine.EngineFixture.graph;
import static com.salian.orchestrator.engine.EngineFixture.list;
import static com.salian.orchestrator.engine.EngineFixture.withRework;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.salian.orchestrator.model.ReworkPolicy;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WorkflowGraphTest {

    private final WorkflowGraph diamond = graph(
            agent("a", "x", list(), list(), list("a")),
            agent("b", "x", list("a"), list("a"), list("b")),
            agent("c", "x", list("a"), list("a"), list("c")),
            agent("d", "x", list("b", "c"), list("b", "c"), list("d")));

    @Test
    void ordersTopologicallyAndGroupsParallelLevels() {
        assertThat(diamond.topologicalOrder()).containsExactly("a", "b", "c", "d");
        assertThat(diamond.parallelLevels()).containsExactly(List.of("a"), List.of("b", "c"), List.of("d"));
    }

    @Test
    void computesDownstreamClosureAndReaders() {
        assertThat(diamond.downstreamClosure(Set.of("b"))).containsExactly("b", "d");
        assertThat(diamond.readersOf("a")).containsExactly("b", "c");
    }

    @Test
    void rejectsCyclesUnknownDependenciesAndInvalidReworkTargets() {
        assertThatThrownBy(() -> graph(
                agent("a", "x", list("b"), list(), list()),
                agent("b", "x", list("a"), list(), list())))
                .hasMessageContaining("cycle");
        assertThatThrownBy(() -> graph(agent("a", "x", list("ghost"), list(), list())))
                .hasMessageContaining("unknown stage");
        assertThatThrownBy(() -> graph(
                agent("a", "x", list(), list(), list()),
                withRework(agent("b", "x", list(), list(), list()), new ReworkPolicy("a", 1, null))))
                .hasMessageContaining("upstream");
    }
}
