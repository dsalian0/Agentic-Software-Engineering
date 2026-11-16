package com.salian.orchestrator.agents;

import com.salian.orchestrator.engine.Agent;
import com.salian.orchestrator.engine.ExecutionMode;
import com.salian.orchestrator.engine.FailureKind;
import com.salian.orchestrator.engine.StageContext;
import com.salian.orchestrator.engine.StageResult;

/**
 * Placeholder for an LLM-backed documentation agent (P1, not part of the MVP). It makes no network
 * call: it reports itself unavailable, which exercises the engine's fallback path to the
 * deterministic {@link TemplateDocWriterAgent}. This is real fallback behaviour, not a simulation:
 * the documentation stage genuinely has no LLM provider in this build.
 */
public final class LlmDocWriterAgent implements Agent {

    @Override
    public String name() {
        return "llm-doc-writer";
    }

    @Override
    public ExecutionMode mode() {
        return ExecutionMode.LLM;
    }

    @Override
    public StageResult execute(StageContext ctx) {
        String reason = System.getenv("ANTHROPIC_API_KEY") == null
                ? "no LLM provider configured (ANTHROPIC_API_KEY not set)"
                : "LLM integration is a P1 item and is disabled in this MVP build";
        return StageResult.fail(FailureKind.PERMANENT, "LLM doc writer unavailable: " + reason);
    }
}
