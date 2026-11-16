package com.salian.orchestrator.agents;

import com.salian.orchestrator.audit.EventType;
import com.salian.orchestrator.engine.Agent;
import com.salian.orchestrator.engine.ExecutionMode;
import com.salian.orchestrator.engine.FailureKind;
import com.salian.orchestrator.engine.StageContext;
import com.salian.orchestrator.engine.StageResult;
import com.salian.orchestrator.policy.PolicyEngine;
import java.util.LinkedHashMap;
import java.util.Map;

/** Runs the policy engine over the run's changes; any BLOCK finding fails the stage as POLICY_BLOCK. */
public final class PolicyScannerAgent implements Agent {

    private final PolicyEngine engine;

    public PolicyScannerAgent(PolicyEngine engine) {
        this.engine = engine;
    }

    @Override
    public String name() {
        return "policy-scanner";
    }

    @Override
    public ExecutionMode mode() {
        return ExecutionMode.REAL;
    }

    @Override
    public StageResult execute(StageContext ctx) {
        PolicyEngine.Result result = engine.evaluate(ctx.workspace());
        for (PolicyEngine.Evaluation evaluation : result.evaluations()) {
            ctx.emit(EventType.POLICY_EVALUATED, Map.of("rule", evaluation.rule(),
                    "severity", evaluation.severity(), "findings", evaluation.findings(),
                    "policyVersion", String.valueOf(engine.config().version())));
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("summary", result.blocking() + " blocking, " + result.warnings() + " warning finding(s) across "
                + result.evaluations().size() + " rules");
        report.put("policyVersion", engine.config().version());
        report.put("blocking", result.blocking());
        report.put("warnings", result.warnings());
        report.put("findings", result.findings());
        report.put("rules", result.evaluations());
        if (result.blocking() > 0) {
            return StageResult.fail(FailureKind.POLICY_BLOCK, "Policy blocked the change: " + result.findings().stream()
                    .filter(f -> f.severity() == com.salian.orchestrator.policy.PolicyConfig.Severity.BLOCK)
                    .map(f -> f.rule() + " @ " + f.file() + ":" + f.line()).toList(), report);
        }
        ctx.output("policy-report", report);
        return StageResult.ok((String) report.get("summary"));
    }
}
