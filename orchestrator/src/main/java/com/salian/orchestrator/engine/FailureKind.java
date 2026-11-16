package com.salian.orchestrator.engine;

public enum FailureKind {
    /** Transient (tool hiccup, flaky infrastructure): eligible for bounded retry. */
    RETRYABLE,
    /** Deterministic failure: retrying the same strategy is pointless; fallback may apply. */
    PERMANENT,
    /** A guardrail blocked the output: never retried or bypassed by fallback; only rework or a human can resolve it. */
    POLICY_BLOCK
}
