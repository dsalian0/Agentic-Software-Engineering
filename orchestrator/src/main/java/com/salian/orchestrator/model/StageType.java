package com.salian.orchestrator.model;

public enum StageType {
    /** Work performed by an agent (scripted or real tool-driven). */
    AGENT,
    /** Human approval checkpoint: the run pauses until a person approves or rejects. */
    APPROVAL,
    /** Human clarification checkpoint: the run pauses until answers are supplied, then an agent applies them. */
    CLARIFICATION
}
