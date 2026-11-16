package com.salian.orchestrator.agents;

import com.salian.orchestrator.engine.StageContext;
import com.salian.orchestrator.scenario.Playbook;
import com.salian.orchestrator.scenario.PlaybookRepository;

/** Shared constants and helpers for the SDLC agents. */
public final class Scenarios {

    /** The project directory inside the sandbox that agents build and change. */
    public static final String PROJECT = "url-shortener";
    public static final String PARAM_SCENARIO = "scenario";
    public static final String PARAM_BASELINE = "baseline";
    public static final String PARAM_MAVEN_OFFLINE = "mavenOffline";

    private Scenarios() {
    }

    public static Playbook playbook(PlaybookRepository playbooks, StageContext ctx) {
        return playbooks.load(ctx.parameter(PARAM_SCENARIO));
    }
}
