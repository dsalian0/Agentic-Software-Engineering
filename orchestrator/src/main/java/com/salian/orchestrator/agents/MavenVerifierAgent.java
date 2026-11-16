package com.salian.orchestrator.agents;

import com.salian.orchestrator.engine.Agent;
import com.salian.orchestrator.engine.ExecutionMode;
import com.salian.orchestrator.engine.FailureKind;
import com.salian.orchestrator.engine.StageContext;
import com.salian.orchestrator.engine.StageResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Runs the real Maven build ({@code mvn -B verify}) of the sandbox project and parses the Surefire
 * reports. Infrastructure-looking failures (artifact download, network) are classified RETRYABLE;
 * compilation and test failures are PERMANENT.
 */
public final class MavenVerifierAgent implements Agent {

    private static final Pattern TRANSIENT = Pattern.compile(
            "Could not transfer artifact|Connection (timed out|reset|refused)|Read timed out|Temporary failure in name resolution");
    private static final Pattern ATTR = Pattern.compile("(tests|errors|skipped|failures)=\"(\\d+)\"");

    private final List<String> mavenCommand;
    private final long timeoutMinutes;

    public MavenVerifierAgent(List<String> mavenCommand, long timeoutMinutes) {
        this.mavenCommand = List.copyOf(mavenCommand);
        this.timeoutMinutes = timeoutMinutes;
    }

    public static List<String> defaultCommand() {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        return windows ? List.of("cmd.exe", "/c", "mvn") : List.of("mvn");
    }

    @Override
    public String name() {
        return "maven-verifier";
    }

    @Override
    public ExecutionMode mode() {
        return ExecutionMode.REAL;
    }

    @Override
    public StageResult execute(StageContext ctx) throws IOException, InterruptedException {
        Path project = ctx.workspace().root().resolve(Scenarios.PROJECT);
        if (!Files.exists(project.resolve("pom.xml"))) {
            return StageResult.fail(FailureKind.PERMANENT, "No pom.xml in sandbox project " + project);
        }
        List<String> command = new ArrayList<>(mavenCommand);
        command.addAll(List.of("-B", "-Dstyle.color=never", "clean", "verify"));
        if ("true".equals(ctx.parameter(Scenarios.PARAM_MAVEN_OFFLINE))) {
            command.add("-o");
        }
        Path log = ctx.runDir().resolve("logs").resolve("build-e" + ctx.execution() + "-a" + ctx.attempt() + ".log");
        Files.createDirectories(log.getParent());
        Instant started = Instant.now();
        Process process = new ProcessBuilder(command).directory(project.toFile())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!process.waitFor(timeoutMinutes, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            return StageResult.fail(FailureKind.RETRYABLE, "Maven build timed out after " + timeoutMinutes + " min");
        }
        long durationMs = Duration.between(started, Instant.now()).toMillis();
        String output = Files.readString(log, StandardCharsets.UTF_8);
        Map<String, Object> report = parseSurefire(project.resolve("target/surefire-reports"));
        report.put("command", String.join(" ", command));
        report.put("exitCode", process.exitValue());
        report.put("durationMs", durationMs);
        report.put("log", ctx.runDir().relativize(log).toString().replace('\\', '/'));
        boolean success = process.exitValue() == 0 && output.contains("BUILD SUCCESS");
        report.put("buildSuccess", success);
        report.put("summary", (success ? "BUILD SUCCESS" : "BUILD FAILURE") + ": " + report.get("tests") + " tests, "
                + report.get("failures") + " failures, " + report.get("errors") + " errors, "
                + report.get("skipped") + " skipped in " + durationMs / 1000 + "s");
        if (!success) {
            FailureKind kind = TRANSIENT.matcher(output).find() ? FailureKind.RETRYABLE : FailureKind.PERMANENT;
            return StageResult.fail(kind, (String) report.get("summary") + firstError(output), report);
        }
        ctx.output("test-report", report);
        return StageResult.ok((String) report.get("summary"));
    }

    static Map<String, Object> parseSurefire(Path reports) throws IOException {
        int tests = 0;
        int failures = 0;
        int errors = 0;
        int skipped = 0;
        List<String> suites = new ArrayList<>();
        if (Files.isDirectory(reports)) {
            try (Stream<Path> files = Files.list(reports)) {
                for (Path file : files.filter(f -> f.getFileName().toString().startsWith("TEST-")).sorted().toList()) {
                    String xml = Files.readString(file, StandardCharsets.UTF_8);
                    int end = xml.indexOf('>', xml.indexOf("<testsuite"));
                    Matcher attrs = ATTR.matcher(xml.substring(xml.indexOf("<testsuite"), end));
                    Map<String, Integer> values = new LinkedHashMap<>();
                    while (attrs.find()) {
                        values.putIfAbsent(attrs.group(1), Integer.parseInt(attrs.group(2)));
                    }
                    tests += values.getOrDefault("tests", 0);
                    failures += values.getOrDefault("failures", 0);
                    errors += values.getOrDefault("errors", 0);
                    skipped += values.getOrDefault("skipped", 0);
                    suites.add(file.getFileName().toString().replace("TEST-", "").replace(".xml", "")
                            + " (" + values.getOrDefault("tests", 0) + ")");
                }
            }
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("tests", tests);
        report.put("failures", failures);
        report.put("errors", errors);
        report.put("skipped", skipped);
        report.put("suites", suites);
        return report;
    }

    private static String firstError(String output) {
        return output.lines().filter(l -> l.startsWith("[ERROR]")).findFirst().map(l -> " | " + l).orElse("");
    }
}
