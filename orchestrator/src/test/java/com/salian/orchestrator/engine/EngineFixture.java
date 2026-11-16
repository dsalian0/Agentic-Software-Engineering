package com.salian.orchestrator.engine;

import com.salian.orchestrator.audit.AuditEvent;
import com.salian.orchestrator.model.Budget;
import com.salian.orchestrator.model.RetryPolicy;
import com.salian.orchestrator.model.ReworkPolicy;
import com.salian.orchestrator.model.StageDefinition;
import com.salian.orchestrator.model.StageType;
import com.salian.orchestrator.model.WorkflowDefinition;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/** Small builders and fakes for engine tests. */
final class EngineFixture {

    private EngineFixture() {
    }

    static StageDefinition agent(String id, String agent, List<String> deps, List<String> reads, List<String> writes) {
        return new StageDefinition(id, StageType.AGENT, id, agent, null, deps, reads, writes, null, List.of(),
                null, null, null);
    }

    static StageDefinition approval(String id, List<String> deps, List<String> reads) {
        return new StageDefinition(id, StageType.APPROVAL, "approve " + id, null, null, deps, reads, List.of(),
                null, List.of(), null, null, null);
    }

    static StageDefinition withRetry(StageDefinition s, RetryPolicy retry) {
        return new StageDefinition(s.id(), s.type(), s.description(), s.agent(), s.fallbackAgent(), s.dependsOn(),
                s.reads(), s.writes(), s.when(), s.exitGates(), retry, s.onFailure(), s.timeoutMs());
    }

    static StageDefinition withFallback(StageDefinition s, String fallback) {
        return new StageDefinition(s.id(), s.type(), s.description(), s.agent(), fallback, s.dependsOn(),
                s.reads(), s.writes(), s.when(), s.exitGates(), s.retry(), s.onFailure(), s.timeoutMs());
    }

    static StageDefinition withRework(StageDefinition s, ReworkPolicy rework) {
        return new StageDefinition(s.id(), s.type(), s.description(), s.agent(), s.fallbackAgent(), s.dependsOn(),
                s.reads(), s.writes(), s.when(), s.exitGates(), s.retry(), rework, s.timeoutMs());
    }

    static StageDefinition withWhen(StageDefinition s, String when) {
        return new StageDefinition(s.id(), s.type(), s.description(), s.agent(), s.fallbackAgent(), s.dependsOn(),
                s.reads(), s.writes(), when, s.exitGates(), s.retry(), s.onFailure(), s.timeoutMs());
    }

    static StageDefinition withTimeout(StageDefinition s, long timeoutMs) {
        return new StageDefinition(s.id(), s.type(), s.description(), s.agent(), s.fallbackAgent(), s.dependsOn(),
                s.reads(), s.writes(), s.when(), s.exitGates(), s.retry(), s.onFailure(), timeoutMs);
    }

    static StageDefinition clarification(String id, String agent, List<String> deps, List<String> reads, List<String> writes) {
        return new StageDefinition(id, StageType.CLARIFICATION, "clarify", agent, null, deps, reads, writes, null,
                List.of(), null, null, null);
    }

    static WorkflowGraph graph(StageDefinition... stages) {
        return graph(Budget.DEFAULT, stages);
    }

    static WorkflowGraph graph(Budget budget, StageDefinition... stages) {
        return new WorkflowGraph(new WorkflowDefinition("test", "1", "test workflow", budget, Arrays.asList(stages)));
    }

    static List<String> list(String... values) {
        return List.of(values);
    }

    static List<String> types(List<AuditEvent> events) {
        return events.stream().map(AuditEvent::type).toList();
    }

    static long count(List<AuditEvent> events, String type) {
        return events.stream().filter(e -> e.type().equals(type)).count();
    }

    /** Agent driven by a lambda; records how many times it ran. */
    static final class FakeAgent implements Agent {
        interface Behavior {
            StageResult run(StageContext ctx, int invocation) throws Exception;
        }

        private final String name;
        private final ExecutionMode mode;
        private volatile Behavior behavior;
        final List<Integer> executions = new CopyOnWriteArrayList<>();

        FakeAgent(String name, Behavior behavior) {
            this(name, ExecutionMode.REAL, behavior);
        }

        FakeAgent(String name, ExecutionMode mode, Behavior behavior) {
            this.name = name;
            this.mode = mode;
            this.behavior = behavior;
        }

        void setBehavior(Behavior behavior) {
            this.behavior = behavior;
        }

        int invocations() {
            return executions.size();
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public ExecutionMode mode() {
            return mode;
        }

        @Override
        public StageResult execute(StageContext ctx) throws Exception {
            executions.add(ctx.execution());
            return behavior.run(ctx, executions.size());
        }
    }

    /** Agent that writes every declared output with a trivial payload. */
    static FakeAgent producing(String name) {
        return new FakeAgent(name, (ctx, n) -> {
            for (String key : ctx.stage().writes()) {
                ctx.output(key, Map.of("summary", key + " from " + ctx.stageId(), "execution", ctx.execution()));
            }
            return StageResult.ok("produced " + ctx.stage().writes());
        });
    }

    static final class RecordingSleeper implements Sleeper {
        final List<Long> delays = new ArrayList<>();

        @Override
        public void sleep(long millis) {
            delays.add(millis);
        }
    }

    /** Clock whose time the test can move forward. */
    static final class MutableClock extends Clock {
        private volatile Instant now = Instant.parse("2026-11-15T12:00:00Z");

        void advanceMillis(long ms) {
            now = now.plusMillis(ms);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            now = now.plusMillis(1);
            return now;
        }
    }

    static Orchestrator orchestrator(Path runsDir, WorkflowGraph graph, Clock clock, Sleeper sleeper, Agent... agents) {
        AgentRegistry registry = new AgentRegistry();
        for (Agent a : agents) {
            registry.register(a);
        }
        return new Orchestrator(graph, registry, new GateRegistry(), new RunStore(runsDir), clock, sleeper, 4);
    }
}
