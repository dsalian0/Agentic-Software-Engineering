package com.salian.orchestrator.engine;

import java.util.List;
import java.util.Optional;

/**
 * Deterministic, declared fault injection used by scenarios to exercise recovery paths. Every
 * injected failure is recorded as {@code FAULT_INJECTED} with {@code injected=true} so it can never
 * be mistaken for an organic failure.
 */
public final class FaultInjector {

    /**
     * @param stage     stage id
     * @param execution which execution of the stage (1 = first; >1 after rework/replan)
     * @param attempt   which attempt within that execution
     */
    public record Fault(String stage, int execution, int attempt, FailureKind kind, String message) {
    }

    public static final FaultInjector NONE = new FaultInjector(List.of());

    private final List<Fault> faults;

    public FaultInjector(List<Fault> faults) {
        this.faults = List.copyOf(faults);
    }

    public Optional<Fault> match(String stage, int execution, int attempt) {
        return faults.stream()
                .filter(f -> f.stage().equals(stage) && f.execution() == execution && f.attempt() == attempt)
                .findFirst();
    }
}
