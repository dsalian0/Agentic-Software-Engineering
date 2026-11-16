package com.salian.orchestrator.policy;

import com.salian.orchestrator.policy.PolicyConfig.Severity;
import com.salian.orchestrator.workspace.Workspace;
import com.salian.orchestrator.workspace.Workspace.JournalEntry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Evaluates guardrails over the files changed in a run (security, privacy/compliance, change
 * control). Pure function of workspace content + journal + config, so results are reproducible.
 */
public final class PolicyEngine {

    public static final String PII_RAW_IP = "pii-raw-ip";
    public static final String SECRETS = "secrets";
    public static final String PROTECTED_PATHS = "protected-paths";
    public static final String MIGRATION_IMMUTABILITY = "migration-immutability";
    public static final String DEPENDENCY_ALLOWLIST = "dependency-allowlist";
    public static final String CHANGE_SIZE = "change-size";
    public static final String TESTS_ACCOMPANY_CODE = "tests-accompany-code";

    private static final Pattern RAW_IP_SQL = Pattern.compile("(?i)\\b(client_ip|ip_address|remote_addr|ip_addr)\\b");
    private static final Pattern RAW_IP_JAVA = Pattern.compile("private\\s+String\\s+(clientIp|ipAddress|remoteAddr)\\s*;");
    private static final List<Pattern> SECRET_PATTERNS = List.of(
            Pattern.compile("AKIA[0-9A-Z]{16}"),
            Pattern.compile("-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----"),
            Pattern.compile("(?i)(api[_-]?key|secret|token)\\s*[:=]\\s*[\"'][A-Za-z0-9_\\-]{16,}[\"']"),
            // Key must start the token (so ${SPRING_DATASOURCE_PASSWORD:...} placeholders do not match).
            Pattern.compile("(?i)(^|[\\s\"'{,])password\\s*[:=]\\s*[\"']?(?!\\$\\{)[^\\s\"'$]{6,}"));
    private static final Pattern DEPENDENCY = Pattern.compile(
            "<dependency>\\s*<groupId>([^<]+)</groupId>\\s*<artifactId>([^<]+)</artifactId>");

    private final PolicyConfig config;

    public PolicyEngine(PolicyConfig config) {
        this.config = config;
    }

    public PolicyConfig config() {
        return config;
    }

    public record Finding(String rule, Severity severity, String file, int line, String message) {
    }

    public record Evaluation(String rule, Severity severity, int findings) {
    }

    public record Result(List<Finding> findings, List<Evaluation> evaluations) {

        public long blocking() {
            return findings.stream().filter(f -> f.severity() == Severity.BLOCK).count();
        }

        public long warnings() {
            return findings.stream().filter(f -> f.severity() == Severity.WARN).count();
        }
    }

    public Result evaluate(Workspace workspace) {
        Map<String, JournalEntry> changed = new LinkedHashMap<>();
        workspace.allJournalEntries().forEach(e -> changed.put(e.path(), e));
        List<Finding> findings = new ArrayList<>();
        List<Evaluation> evaluations = new ArrayList<>();

        rule(PII_RAW_IP, evaluations, findings, () -> {
            List<Finding> out = new ArrayList<>();
            for (String path : changed.keySet()) {
                String content = workspace.read(path).orElse("");
                if (path.endsWith(".sql")) {
                    scanLines(path, content, RAW_IP_SQL, PII_RAW_IP,
                            "raw client IP address persisted in schema; store a salted hash instead", out);
                } else if (path.endsWith(".java") && path.contains("/src/main/")) {
                    scanLines(path, content, RAW_IP_JAVA, PII_RAW_IP, "raw client IP held in an entity field", out);
                }
            }
            return out;
        });
        rule(SECRETS, evaluations, findings, () -> {
            List<Finding> out = new ArrayList<>();
            for (String path : changed.keySet()) {
                String content = workspace.read(path).orElse("");
                for (Pattern pattern : SECRET_PATTERNS) {
                    scanLines(path, content, pattern, SECRETS, "possible hard-coded secret", out);
                }
            }
            return out;
        });
        rule(PROTECTED_PATHS, evaluations, findings, () -> changed.keySet().stream()
                .filter(path -> config.allowedPathPrefixes().stream().noneMatch(path::startsWith))
                .map(path -> new Finding(PROTECTED_PATHS, null, path, 0,
                        "change outside allowed paths " + config.allowedPathPrefixes()))
                .toList());
        rule(MIGRATION_IMMUTABILITY, evaluations, findings, () -> changed.values().stream()
                .filter(e -> e.path().contains("/db/migration/") && e.existedBefore())
                .map(e -> new Finding(MIGRATION_IMMUTABILITY, null, e.path(), 0,
                        "existing migration modified; add a new V<n>__*.sql instead"))
                .toList());
        rule(DEPENDENCY_ALLOWLIST, evaluations, findings, () -> {
            List<Finding> out = new ArrayList<>();
            for (String path : changed.keySet()) {
                if (!path.endsWith("pom.xml")) {
                    continue;
                }
                Matcher m = DEPENDENCY.matcher(workspace.read(path).orElse(""));
                while (m.find()) {
                    String coordinate = m.group(1).trim() + ":" + m.group(2).trim();
                    if (!allowed(coordinate)) {
                        out.add(new Finding(DEPENDENCY_ALLOWLIST, null, path, 0, "dependency not on allowlist: " + coordinate));
                    }
                }
            }
            return out;
        });
        rule(CHANGE_SIZE, evaluations, findings, () -> changed.size() > config.maxChangedFiles()
                ? List.of(new Finding(CHANGE_SIZE, null, "*", 0,
                changed.size() + " files changed (limit " + config.maxChangedFiles() + "); split the change"))
                : List.of());
        rule(TESTS_ACCOMPANY_CODE, evaluations, findings, () -> {
            boolean mainChanged = changed.keySet().stream().anyMatch(p -> p.contains("/src/main/java/"));
            boolean testsChanged = changed.keySet().stream().anyMatch(p -> p.contains("/src/test/"));
            return mainChanged && !testsChanged
                    ? List.of(new Finding(TESTS_ACCOMPANY_CODE, null, "*", 0, "production code changed without test changes"))
                    : List.of();
        });
        return new Result(findings, evaluations);
    }

    private interface RuleBody {
        List<Finding> run();
    }

    private void rule(String rule, List<Evaluation> evaluations, List<Finding> findings, RuleBody body) {
        Severity severity = config.severity(rule);
        if (severity == Severity.OFF) {
            evaluations.add(new Evaluation(rule, severity, 0));
            return;
        }
        List<Finding> raw = body.run();
        raw.forEach(f -> findings.add(new Finding(f.rule(), severity, f.file(), f.line(), f.message())));
        evaluations.add(new Evaluation(rule, severity, raw.size()));
    }

    private boolean allowed(String coordinate) {
        return config.dependencyAllowlist().stream().anyMatch(entry -> entry.endsWith(":*")
                ? coordinate.startsWith(entry.substring(0, entry.length() - 1))
                : coordinate.equals(entry));
    }

    private static void scanLines(String path, String content, Pattern pattern, String rule, String message, List<Finding> out) {
        String[] lines = content.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (pattern.matcher(lines[i]).find()) {
                out.add(new Finding(rule, null, path, i + 1, message + ": " + lines[i].trim()));
            }
        }
    }
}
