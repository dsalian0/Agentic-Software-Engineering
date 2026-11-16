package com.salian.orchestrator.scenario;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.salian.orchestrator.engine.FailureKind;
import java.util.List;
import java.util.Map;

/**
 * A scenario definition. The requirement text, feature catalog and ambiguity options are inputs the
 * agents reason over; the {@code main}/{@code tests} overlays are the pre-authored code the SCRIPTED
 * implementation agents apply. Nothing here is executed directly by the engine.
 *
 * @param baseline        scenarios whose final code seeds the sandbox before the run (brownfield)
 * @param clarificationAnswers answers the demo driver submits (as a labelled delegated human)
 * @param injectedDefects deliberately flawed file versions applied on the first implementation, to
 *                        demonstrate guardrails; removed when policy feedback names {@code rule}
 */
public record Playbook(
        String id,
        String title,
        String kind,
        String requirement,
        List<String> baseline,
        List<Feature> features,
        List<Ambiguity> ambiguities,
        Map<String, String> clarificationAnswers,
        Amendment amendment,
        List<FaultSpec> faults,
        List<Defect> injectedDefects) {

    public Playbook {
        baseline = baseline == null ? List.of() : List.copyOf(baseline);
        features = features == null ? List.of() : List.copyOf(features);
        ambiguities = ambiguities == null ? List.of() : List.copyOf(ambiguities);
        clarificationAnswers = clarificationAnswers == null ? Map.of() : Map.copyOf(clarificationAnswers);
        faults = faults == null ? List.of() : List.copyOf(faults);
        injectedDefects = injectedDefects == null ? List.of() : List.copyOf(injectedDefects);
    }

    public Feature feature(String id) {
        return features.stream().filter(f -> f.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown feature '" + id + "' in playbook " + this.id));
    }

    public record Feature(String id, String title, List<String> triggers, List<String> acceptance,
                          List<Overlay> main, List<Overlay> tests) {
        public Feature {
            triggers = triggers == null ? List.of() : List.copyOf(triggers);
            acceptance = acceptance == null ? List.of() : List.copyOf(acceptance);
            main = main == null ? List.of() : List.copyOf(main);
            tests = tests == null ? List.of() : List.copyOf(tests);
        }
    }

    /** A file to place in the sandbox: {@code from} (relative to the playbook) or, if absent, the repo file at {@code path}. */
    public record Overlay(String path, String from) {
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public static Overlay of(String path) {
            return new Overlay(path, null);
        }

        @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
        public static Overlay of(@com.fasterxml.jackson.annotation.JsonProperty("path") String path,
                                 @com.fasterxml.jackson.annotation.JsonProperty("from") String from) {
            return new Overlay(path, from);
        }
    }

    public record Ambiguity(String term, String question, List<Option> options) {
    }

    public record Option(String id, String label, boolean outOfScope) {
    }

    public record Amendment(String note, String text, List<String> addFeatures) {
    }

    public record FaultSpec(String stage, int execution, int attempt, FailureKind kind, String message) {
    }

    public record Defect(String path, String from, String rule, String label) {
    }
}
