package com.salian.orchestrator.engine;

/**
 * A unit of autonomous work. Agents receive a {@link StageContext} (inputs, sandbox, decision log)
 * and may only affect the world through it; the engine decides whether their output is accepted.
 */
public interface Agent {

    String name();

    ExecutionMode mode();

    StageResult execute(StageContext context) throws Exception;
}
