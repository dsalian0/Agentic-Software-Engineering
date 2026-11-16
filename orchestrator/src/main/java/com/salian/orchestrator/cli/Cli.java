package com.salian.orchestrator.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.salian.orchestrator.audit.AuditEvent;
import com.salian.orchestrator.audit.AuditLog;
import com.salian.orchestrator.engine.Orchestrator;
import com.salian.orchestrator.engine.RunStore;
import com.salian.orchestrator.engine.Sleeper;
import com.salian.orchestrator.metrics.MetricsCalculator;
import com.salian.orchestrator.metrics.ReportWriter;
import com.salian.orchestrator.metrics.RunMetrics;
import com.salian.orchestrator.model.RunState;
import com.salian.orchestrator.model.StageState;
import com.salian.orchestrator.scenario.ScenarioService;
import com.salian.orchestrator.support.Json;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Command-line interface. Every human action (approve, reject, clarify, amend, stop) goes through here. */
public final class Cli {

    private static final String USAGE = """
            Usage: orch <command> [args] [--repo <path>]

            Scenarios
              demo <greenfield|brownfield|ambiguous|safe-stop|all> [--offline]
                                              scripted end-to-end demo (human steps are DELEGATED and labelled)
              run <scenario> [--run-id id] [--auto-approve-as name] [--offline]
                                              start a run; pauses at human checkpoints unless auto-approved
              resume <runId>                  continue after a checkpoint, replan or safe-stop

            Human controls
              approve <runId> <stage> --by <name> [--comment text]
              reject  <runId> <stage> --by <name> [--comment text]
              clarify <runId> <stage> <answers.json> --by <name>
              amend   <runId> <artifact> <content.json> --by <name> --reason <text>
              stop    <runId> --by <name> --reason <text>        (kill switch)

            Inspection
              list | status <runId> | report <runId> | metrics | verify-audit <runId>
              lineage <runId> <artifact[@version]> | artifact <runId> <artifact[@version]>
            """;

    private final PrintStream out;

    public Cli(PrintStream out) {
        this.out = out;
    }

    public int run(String[] argv) throws Exception {
        Args args = Args.parse(argv);
        if (args.positional.isEmpty() || args.flags.containsKey("help")) {
            out.print(USAGE);
            return args.positional.isEmpty() ? 2 : 0;
        }
        OrchestratorFactory.Paths paths = OrchestratorFactory.locate(Path.of(args.flags.getOrDefault("repo", ".")));
        ScenarioService scenarios = OrchestratorFactory.create(paths, paths.runs(), null, Clock.systemUTC(), Sleeper.REAL);
        Orchestrator orchestrator = scenarios.orchestrator();
        orchestrator.setEventListener(new EventPrinter(out));
        String command = args.positional.get(0);
        boolean offline = args.flags.containsKey("offline");
        switch (command) {
            case "demo" -> {
                DemoDriver demo = new DemoDriver(scenarios, out);
                String which = args.arg(1, "scenario");
                List<RunState> results = new ArrayList<>();
                if (which.equals("greenfield") || which.equals("all")) {
                    results.add(demo.greenfield(offline));
                }
                if (which.equals("brownfield") || which.equals("all")) {
                    results.add(demo.brownfield(offline));
                }
                if (which.equals("ambiguous") || which.equals("all")) {
                    results.add(demo.ambiguous(offline));
                }
                if (which.equals("safe-stop") || which.equals("all")) {
                    results.add(demo.safeStop());
                }
                if (results.isEmpty()) {
                    throw new IllegalArgumentException("Unknown demo: " + which);
                }
                for (RunState state : results) {
                    report(orchestrator, state);
                }
                printAggregate(orchestrator.store(), orchestrator);
            }
            case "run" -> {
                String scenario = args.arg(1, "scenario");
                String runId = args.flags.getOrDefault("run-id", scenarios.newRunId(scenario));
                RunState state = scenarios.start(scenario, runId, args.flags.get("auto-approve-as"), offline);
                after(state);
            }
            case "resume" -> after(scenarios.resume(args.arg(1, "runId")));
            case "approve", "reject" -> {
                String runId = args.arg(1, "runId");
                String stage = args.arg(2, "stage");
                String by = args.required("by");
                if (command.equals("approve")) {
                    orchestrator.approve(runId, stage, by, args.flags.get("comment"));
                } else {
                    orchestrator.reject(runId, stage, by, args.flags.get("comment"));
                }
                out.println("Recorded " + command + " for " + stage + " by human:" + by + ". Continue with: orch resume " + runId);
            }
            case "clarify" -> {
                String runId = args.arg(1, "runId");
                JsonNode answers = Json.mapper().readTree(Path.of(args.arg(3, "answers.json")).toFile());
                orchestrator.clarify(runId, args.arg(2, "stage"), answers, args.required("by"), false);
                out.println("Answers recorded. Continue with: orch resume " + runId);
            }
            case "amend" -> {
                String runId = args.arg(1, "runId");
                JsonNode content = Json.mapper().readTree(Path.of(args.arg(3, "content.json")).toFile());
                RunState state = orchestrator.amend(runId, args.arg(2, "artifact"), content, args.required("by"),
                        args.required("reason"));
                out.println(state.getStatusReason() + ". Continue with: orch resume " + runId);
            }
            case "stop" -> {
                String runId = args.arg(1, "runId");
                orchestrator.requestStop(runId, args.required("by"), args.required("reason"));
                out.println("Kill switch set for " + runId + "; the engine safe-stops at its next checkpoint.");
            }
            case "list" -> {
                for (String runId : orchestrator.store().listRunIds()) {
                    RunState s = orchestrator.store().load(runId);
                    out.printf("%-34s %-11s %-22s %s%n", runId, s.getScenario(), s.getStatus(),
                            s.getStatusReason() == null ? "" : s.getStatusReason());
                }
            }
            case "status" -> printStatus(orchestrator.store().load(args.arg(1, "runId")));
            case "report" -> report(orchestrator, orchestrator.store().load(args.arg(1, "runId")));
            case "metrics" -> printAggregate(orchestrator.store(), orchestrator);
            case "verify-audit" -> {
                AuditLog.VerificationResult result = orchestrator.auditLog(args.arg(1, "runId")).verify();
                out.println((result.intact() ? "INTACT" : "BROKEN") + ": " + result.events() + " events");
                result.problems().forEach(p -> out.println("  " + p));
                return result.intact() ? 0 : 1;
            }
            case "lineage" -> {
                RunState state = orchestrator.store().load(args.arg(1, "runId"));
                ReportWriter.lineage(state, args.arg(2, "artifact")).forEach(out::println);
            }
            case "artifact" -> {
                RunState state = orchestrator.store().load(args.arg(1, "runId"));
                String ref = args.arg(2, "artifact");
                int at = ref.lastIndexOf('@');
                String key = at < 0 ? ref : ref.substring(0, at);
                state.getArtifacts().getOrDefault(key, List.of()).stream()
                        .filter(v -> at < 0 ? !v.isStale() : v.ref().equals(ref))
                        .reduce((a, b) -> b)
                        .ifPresentOrElse(v -> out.println(Json.pretty(v)), () -> out.println("No such artifact: " + ref));
            }
            default -> {
                out.print(USAGE);
                return 2;
            }
        }
        return 0;
    }

    private void after(RunState state) {
        printStatus(state);
        switch (state.getStatus()) {
            case WAITING_APPROVAL -> state.getStages().values().stream()
                    .filter(s -> s.getStatus().name().equals("WAITING_APPROVAL"))
                    .forEach(s -> out.println("Next: orch approve " + state.getRunId() + " " + s.getStageId()
                            + " --by <name>   (or reject), then: orch resume " + state.getRunId()));
            case WAITING_CLARIFICATION -> out.println("Next: orch artifact " + state.getRunId()
                    + " ambiguity-report   then: orch clarify " + state.getRunId() + " clarify <answers.json> --by <name>");
            case SAFE_STOPPED -> out.println("Safe-stopped. Fix the cause, then: orch resume " + state.getRunId());
            default -> { }
        }
    }

    private void report(Orchestrator orchestrator, RunState state) {
        List<AuditEvent> events = orchestrator.auditLog(state.getRunId()).events();
        Path report = ReportWriter.write(state, events, orchestrator.store().runDir(state.getRunId()));
        RunMetrics m = MetricsCalculator.forRun(state.getRunId(), state.getScenario(), state.getStatus().name(), events);
        out.println();
        out.printf("Report: %s%n", report);
        out.printf("  %s -> %s | e2e %d ms (active %d) | agent invocations %d | retries %d | fallbacks %d | "
                        + "rollbacks %d | reworks %d | replans %d | safe-stops %d | MTTR %d ms | audit %s%n",
                state.getRunId(), state.getStatus(), m.endToEndMs(), m.activeMs(), m.agentInvocations(), m.retries(),
                m.fallbacks(), m.rollbacks(), m.reworks(), m.replans(), m.safeStops(), m.mttrMs(),
                m.auditChainIntact() ? "intact" : "BROKEN");
    }

    private void printAggregate(RunStore store, Orchestrator orchestrator) {
        List<RunMetrics> all = new ArrayList<>();
        for (String runId : store.listRunIds()) {
            RunState s = store.load(runId);
            all.add(MetricsCalculator.forRun(runId, s.getScenario(), s.getStatus().name(),
                    orchestrator.auditLog(runId).events()));
        }
        MetricsCalculator.AggregateMetrics a = MetricsCalculator.aggregate(all);
        out.println();
        out.println("Aggregate reliability metrics over " + a.runs() + " run(s) in " + store.runsDir());
        out.println(Json.pretty(a));
        try {
            Files.writeString(store.runsDir().resolve("metrics-summary.json"), Json.pretty(a));
        } catch (Exception ex) {
            out.println("(could not write metrics-summary.json: " + ex.getMessage() + ")");
        }
    }

    private void printStatus(RunState state) {
        out.println();
        out.println("Run " + state.getRunId() + " [" + state.getScenario() + "]: " + state.getStatus()
                + (state.getStatusReason() == null ? "" : " - " + state.getStatusReason()));
        for (StageState s : state.getStages().values()) {
            out.printf("  %-18s %-22s %-20s %s%n", s.getStageId(), s.getStatus(),
                    s.getAgentUsed() == null ? "" : s.getAgentUsed() + (s.getExecutionMode() == null ? "" : "/" + s.getExecutionMode()),
                    s.getSummary() == null ? (s.getLastError() == null ? "" : s.getLastError()) : s.getSummary());
        }
    }

    record Args(List<String> positional, Map<String, String> flags) {
        static Args parse(String[] argv) {
            List<String> positional = new ArrayList<>();
            Map<String, String> flags = new HashMap<>();
            for (int i = 0; i < argv.length; i++) {
                if (argv[i].startsWith("--")) {
                    String key = argv[i].substring(2);
                    if (i + 1 < argv.length && !argv[i + 1].startsWith("--")) {
                        flags.put(key, argv[++i]);
                    } else {
                        flags.put(key, "true");
                    }
                } else {
                    positional.add(argv[i]);
                }
            }
            return new Args(positional, flags);
        }

        String arg(int index, String name) {
            if (positional.size() <= index) {
                throw new IllegalArgumentException("Missing argument <" + name + ">");
            }
            return positional.get(index);
        }

        String required(String flag) {
            String value = flags.get(flag);
            if (value == null || value.isBlank() || value.equals("true")) {
                throw new IllegalArgumentException("Missing required option --" + flag);
            }
            return value;
        }
    }
}
