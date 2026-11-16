package com.salian.orchestrator.cli;

import com.salian.orchestrator.agents.ClarificationApplierAgent;
import com.salian.orchestrator.agents.ContractCheckerAgent;
import com.salian.orchestrator.agents.DesignerAgent;
import com.salian.orchestrator.agents.Gates;
import com.salian.orchestrator.agents.ImpactAnalyzerAgent;
import com.salian.orchestrator.agents.LlmDocWriterAgent;
import com.salian.orchestrator.agents.MavenVerifierAgent;
import com.salian.orchestrator.agents.OverlayApplierAgent;
import com.salian.orchestrator.agents.PlannerAgent;
import com.salian.orchestrator.agents.PolicyScannerAgent;
import com.salian.orchestrator.agents.PublisherAgent;
import com.salian.orchestrator.agents.ReleaseManagerAgent;
import com.salian.orchestrator.agents.RequirementAnalystAgent;
import com.salian.orchestrator.agents.TemplateDocWriterAgent;
import com.salian.orchestrator.engine.Agent;
import com.salian.orchestrator.engine.AgentRegistry;
import com.salian.orchestrator.engine.Orchestrator;
import com.salian.orchestrator.engine.RunStore;
import com.salian.orchestrator.engine.Sleeper;
import com.salian.orchestrator.engine.WorkflowLoader;
import com.salian.orchestrator.policy.PolicyConfig;
import com.salian.orchestrator.policy.PolicyEngine;
import com.salian.orchestrator.scenario.PlaybookRepository;
import com.salian.orchestrator.scenario.ScenarioService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;

/** Wires the engine, agents, gates and policies from the repository layout. */
public final class OrchestratorFactory {

    private OrchestratorFactory() {
    }

    public record Paths(Path repoRoot, Path orchestratorDir) {
        public Path workflow() {
            return orchestratorDir.resolve("workflows/sdlc.yaml");
        }

        public Path policy() {
            return orchestratorDir.resolve("policies/policy.yaml");
        }

        public Path playbooks() {
            return orchestratorDir.resolve("playbooks");
        }

        public Path runs() {
            return orchestratorDir.resolve("runs");
        }
    }

    /** Finds the repository root by walking up from {@code start} to a directory containing orchestrator/workflows. */
    public static Paths locate(Path start) {
        Path dir = start.toAbsolutePath().normalize();
        while (dir != null) {
            if (Files.exists(dir.resolve("orchestrator/workflows/sdlc.yaml"))) {
                return new Paths(dir, dir.resolve("orchestrator"));
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("Cannot find orchestrator/workflows/sdlc.yaml above " + start
                + " (run from the repository or pass --repo <path>)");
    }

    public static ScenarioService create(Paths paths, Path runsDir, Agent verifierOverride, Clock clock, Sleeper sleeper) {
        PlaybookRepository playbooks = new PlaybookRepository(paths.playbooks(), paths.repoRoot());
        PolicyEngine policy = new PolicyEngine(PolicyConfig.load(paths.policy()));
        AgentRegistry agents = new AgentRegistry()
                .register(new RequirementAnalystAgent(playbooks))
                .register(new ClarificationApplierAgent(playbooks))
                .register(new PlannerAgent(playbooks))
                .register(new ImpactAnalyzerAgent(playbooks))
                .register(new DesignerAgent(playbooks))
                .register(new OverlayApplierAgent(playbooks, OverlayApplierAgent.Target.MAIN))
                .register(new OverlayApplierAgent(playbooks, OverlayApplierAgent.Target.TESTS))
                .register(new LlmDocWriterAgent())
                .register(new TemplateDocWriterAgent())
                .register(verifierOverride != null ? verifierOverride
                        : new MavenVerifierAgent(MavenVerifierAgent.defaultCommand(), 15))
                .register(new PolicyScannerAgent(policy))
                .register(new ContractCheckerAgent())
                .register(new ReleaseManagerAgent())
                .register(new PublisherAgent());
        Orchestrator orchestrator = new Orchestrator(WorkflowLoader.load(paths.workflow()), agents, Gates.registry(),
                new RunStore(runsDir), clock, sleeper, 4);
        return new ScenarioService(orchestrator, playbooks, clock);
    }
}
