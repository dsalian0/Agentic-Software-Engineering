package com.salian.orchestrator.agents;

import com.salian.orchestrator.engine.Agent;
import com.salian.orchestrator.engine.ExecutionMode;
import com.salian.orchestrator.engine.StageContext;
import com.salian.orchestrator.engine.StageResult;
import com.salian.orchestrator.scenario.Playbook;
import com.salian.orchestrator.scenario.PlaybookRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normalizes a free-text requirement into a structured engineering problem and scores its ambiguity.
 * Deterministic rules (REAL mode): capability detection against the scenario's feature catalog,
 * a vague-term lexicon, and checks for missing measurable criteria. It never invents capabilities:
 * if nothing concrete is identified, it asks.
 */
public final class RequirementAnalystAgent implements Agent {

    /** Words that signal a quality goal without a testable definition. */
    static final List<String> VAGUE_TERMS = List.of("secure", "safe", "reliable", "robust", "fast", "performant",
            "perform", "scalable", "better", "user-friendly", "intuitive", "efficient", "flexible", "modern",
            "seamless", "simple", "high quality", "resilient");

    private static final Pattern MEASURABLE = Pattern.compile("\\b\\d+(\\.\\d+)?\\s*(ms|s|seconds|%|rps|requests|days|chars|characters)\\b");

    private final PlaybookRepository playbooks;

    public RequirementAnalystAgent(PlaybookRepository playbooks) {
        this.playbooks = playbooks;
    }

    @Override
    public String name() {
        return "requirement-analyst";
    }

    @Override
    public ExecutionMode mode() {
        return ExecutionMode.REAL;
    }

    @Override
    public StageResult execute(StageContext ctx) {
        Playbook playbook = Scenarios.playbook(playbooks, ctx);
        String text = playbook.requirement().trim();
        String lower = text.toLowerCase(Locale.ROOT);

        List<Map<String, Object>> features = new ArrayList<>();
        List<String> acceptance = new ArrayList<>();
        for (Playbook.Feature feature : playbook.features()) {
            List<String> hits = feature.triggers().stream().filter(t -> lower.contains(t.toLowerCase(Locale.ROOT))).toList();
            if (!hits.isEmpty()) {
                features.add(Map.of("id", feature.id(), "title", feature.title(), "matchedOn", hits));
                acceptance.addAll(feature.acceptance());
            }
        }

        Set<String> vague = new LinkedHashSet<>();
        for (String term : VAGUE_TERMS) {
            if (Pattern.compile("\\b" + Pattern.quote(term) + "\\b").matcher(lower).find()) {
                vague.add(term);
            }
        }
        boolean measurable = MEASURABLE.matcher(lower).find();

        List<Map<String, Object>> questions = new ArrayList<>();
        for (String term : vague) {
            Playbook.Ambiguity known = playbook.ambiguities().stream()
                    .filter(a -> a.term().equalsIgnoreCase(term)).findFirst().orElse(null);
            Map<String, Object> q = new LinkedHashMap<>();
            q.put("id", term);
            q.put("term", term);
            if (known != null) {
                q.put("question", known.question());
                q.put("options", known.options());
            } else {
                q.put("question", "'" + term + "' has no testable definition. What measurable acceptance criterion applies?");
                q.put("options", List.of());
            }
            questions.add(q);
        }
        List<String> missing = new ArrayList<>();
        if (features.isEmpty()) {
            missing.add("no concrete capability could be identified from the text");
        }
        if (!vague.isEmpty() && !measurable) {
            missing.add("quality goals are stated without measurable thresholds");
        }

        boolean requiresClarification = !questions.isEmpty() && (features.isEmpty() || !measurable);
        double score = Math.min(1.0, 0.25 * vague.size() + (features.isEmpty() ? 0.5 : 0.0));

        String kind = playbook.kind();
        List<String> constraints = new ArrayList<>(List.of(
                "Java 21 / Spring Boot 3, consistent with repository conventions (AGENTS.md)",
                "All changes confined to the url-shortener project; the Product API must not be modified",
                "Flyway owns the schema: migrations are append-only"));
        if (!"GREENFIELD".equals(kind)) {
            constraints.add("Existing public endpoints must remain backward compatible");
            constraints.add("Existing tests must keep passing and must not be removed");
        }

        Map<String, Object> requirement = new LinkedHashMap<>();
        requirement.put("summary", features.isEmpty()
                ? "Unresolved request: " + abbreviate(text)
                : kind + ": " + String.join("; ", features.stream().map(f -> (String) f.get("title")).toList()));
        requirement.put("rawText", text);
        requirement.put("kind", kind);
        requirement.put("features", features);
        requirement.put("acceptanceCriteria", acceptance);
        requirement.put("constraints", constraints);
        requirement.put("vagueTerms", new ArrayList<>(vague));
        requirement.put("outOfScope", List.of());
        requirement.put("assumptions", features.isEmpty() ? List.of() : List.of(
                "Single-region deployment; horizontal scaling handled by stateless instances behind a load balancer"));

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("summary", requiresClarification
                ? "Ambiguous: " + vague.size() + " undefined quality term(s), " + features.size() + " concrete capability(ies)"
                : "Clear enough to plan: " + features.size() + " capability(ies) identified");
        report.put("requiresClarification", requiresClarification);
        report.put("ambiguityScore", score);
        report.put("vagueTerms", new ArrayList<>(vague));
        report.put("missing", missing);
        report.put("questions", questions);
        report.put("draftRequirement", requirement);

        ctx.output("requirement", requirement);
        ctx.output("ambiguity-report", report);
        ctx.decide(requiresClarification ? "Pause for human clarification before planning"
                        : "Proceed to planning without clarification",
                requiresClarification
                        ? "Terms " + vague + " have no testable meaning and " + (features.isEmpty()
                        ? "no capability was identified" : "no thresholds were given") + "; guessing would risk building the wrong thing"
                        : "Identified capabilities " + features.stream().map(f -> f.get("id")).toList()
                        + (vague.isEmpty() ? " and no undefined quality terms" : "; quality terms are bounded by measurable criteria"),
                List.of("Assume defaults for vague terms and proceed (rejected: unverifiable acceptance)",
                        "Ask the requester (chosen when ambiguity blocks acceptance criteria)"));
        return StageResult.ok((String) requirement.get("summary"));
    }

    private static String abbreviate(String text) {
        return text.length() <= 80 ? text : text.substring(0, 77) + "...";
    }

    static boolean containsMeasurable(String text) {
        Matcher m = MEASURABLE.matcher(text.toLowerCase(Locale.ROOT));
        return m.find();
    }
}
