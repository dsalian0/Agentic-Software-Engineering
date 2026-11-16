package com.salian.orchestrator.engine;

/**
 * Honesty label recorded on every agent invocation (audit + reports).
 */
public enum ExecutionMode {
    /** Deterministic logic computed at run time from real inputs (analysis, planning, scanning, building). */
    REAL,
    /** Output content comes from a pre-authored playbook (e.g. code patches); the agent only selects and applies it. */
    SCRIPTED,
    /** Would call an external model; reports whether it actually did. */
    LLM
}
