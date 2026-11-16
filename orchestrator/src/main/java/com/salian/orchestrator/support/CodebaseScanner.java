package com.salian.orchestrator.support;

import com.salian.orchestrator.workspace.Workspace;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight static analysis of a Spring Boot codebase in the sandbox: components, HTTP endpoints,
 * application events (publishers/listeners), JPA tables, migrations and test counts. Regex based by
 * design (no compiler dependency); good enough for impact analysis of conventional Spring code.
 */
public final class CodebaseScanner {

    private static final Pattern PACKAGE = Pattern.compile("^package\\s+([\\w.]+);", Pattern.MULTILINE);
    private static final Pattern CLASS_LEVEL_MAPPING = Pattern.compile("@RequestMapping\\(\"([^\"]*)\"\\)\\s*(?:@\\w+[^\\n]*\\s*)*public\\s+class");
    private static final Pattern METHOD_MAPPING = Pattern.compile(
            "@(Get|Post|Put|Patch|Delete)Mapping(?:\\(\\s*(?:value\\s*=\\s*)?\"([^\"]*)\"[^)]*\\)|\\(\\))?");
    private static final Pattern PUBLISHES = Pattern.compile("publishEvent\\(\\s*new\\s+(\\w+)\\(");
    private static final Pattern LISTENS = Pattern.compile("@EventListener\\s+(?:public\\s+)?void\\s+\\w+\\(\\s*(\\w+)\\s");
    private static final Pattern TABLE = Pattern.compile("@Table\\(name\\s*=\\s*\"(\\w+)\"");
    private static final Pattern TEST = Pattern.compile("@(Test|ParameterizedTest)\\b");
    private static final List<String> STEREOTYPES = List.of("RestController", "Controller", "Service", "Repository",
            "Component", "Configuration", "Entity", "RestControllerAdvice", "ConfigurationProperties");

    private CodebaseScanner() {
    }

    public record JavaClass(String path, String packageName, String name, List<String> stereotypes,
                            List<String> endpoints, List<String> publishes, List<String> listens, String table) {
    }

    public record Model(String root, List<JavaClass> classes, List<String> migrations, int testMethods,
                        List<String> endpoints) {

        public Set<String> packages() {
            Set<String> packages = new LinkedHashSet<>();
            classes.forEach(c -> packages.add(c.packageName()));
            return packages;
        }
    }

    public static Model scan(Workspace workspace, String projectDir) {
        List<JavaClass> classes = new ArrayList<>();
        List<String> migrations = new ArrayList<>();
        List<String> endpoints = new ArrayList<>();
        int tests = 0;
        for (String path : workspace.list(projectDir)) {
            String source = workspace.read(path).orElse("");
            if (path.contains("/db/migration/") && path.endsWith(".sql")) {
                migrations.add(path.substring(path.lastIndexOf('/') + 1));
            }
            if (!path.endsWith(".java")) {
                continue;
            }
            if (path.contains("/src/test/")) {
                tests += count(TEST, source);
                continue;
            }
            JavaClass javaClass = analyze(path, source);
            classes.add(javaClass);
            endpoints.addAll(javaClass.endpoints());
        }
        return new Model(projectDir, classes, migrations, tests, endpoints.stream().sorted().toList());
    }

    public static JavaClass analyze(String path, String source) {
        String name = path.substring(path.lastIndexOf('/') + 1).replace(".java", "");
        Matcher pkg = PACKAGE.matcher(source);
        String packageName = pkg.find() ? pkg.group(1) : "";
        List<String> stereotypes = STEREOTYPES.stream().filter(s -> source.contains("@" + s + "\n")
                || source.contains("@" + s + "(") || source.contains("@" + s + "\r")).toList();
        String prefix = "";
        Matcher classMapping = CLASS_LEVEL_MAPPING.matcher(source);
        if (classMapping.find()) {
            prefix = classMapping.group(1);
        }
        List<String> endpoints = new ArrayList<>();
        Matcher method = METHOD_MAPPING.matcher(source);
        while (method.find()) {
            String sub = method.group(2) == null ? "" : method.group(2);
            endpoints.add(method.group(1).toUpperCase() + " " + (prefix + sub));
        }
        Matcher table = TABLE.matcher(source);
        return new JavaClass(path, packageName, name, stereotypes, endpoints,
                all(PUBLISHES, source), all(LISTENS, source), table.find() ? table.group(1) : null);
    }

    public static int countTests(Workspace workspace, String projectDir) {
        int tests = 0;
        for (String path : workspace.list(projectDir)) {
            if (path.contains("/src/test/") && path.endsWith(".java")) {
                tests += count(TEST, workspace.read(path).orElse(""));
            }
        }
        return tests;
    }

    private static List<String> all(Pattern pattern, String source) {
        List<String> values = new ArrayList<>();
        Matcher m = pattern.matcher(source);
        while (m.find()) {
            values.add(m.group(1));
        }
        return values;
    }

    private static int count(Pattern pattern, String source) {
        int n = 0;
        Matcher m = pattern.matcher(source);
        while (m.find()) {
            n++;
        }
        return n;
    }
}
