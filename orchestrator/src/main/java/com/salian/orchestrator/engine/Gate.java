package com.salian.orchestrator.engine;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

/** An exit-gate predicate evaluated on a stage's proposed outputs before they are committed. */
public interface Gate {

    String name();

    GateResult evaluate(StageContext context, Map<String, JsonNode> outputs);

    record GateResult(String gate, boolean passed, String detail, FailureKind failureKind) {

        public static GateResult pass(String gate, String detail) {
            return new GateResult(gate, true, detail, null);
        }

        public static GateResult fail(String gate, String detail, FailureKind kind) {
            return new GateResult(gate, false, detail, kind);
        }
    }
}
