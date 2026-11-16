package com.salian.orchestrator.scenario;

import com.salian.orchestrator.engine.FaultInjector;
import com.salian.orchestrator.support.Json;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Loads playbooks and resolves overlay content (from the playbook folder or the repository). */
public final class PlaybookRepository {

    private final Path playbooksDir;
    private final Path repoRoot;
    private final Map<String, Playbook> cache = new ConcurrentHashMap<>();

    public PlaybookRepository(Path playbooksDir, Path repoRoot) {
        this.playbooksDir = playbooksDir.toAbsolutePath().normalize();
        this.repoRoot = repoRoot.toAbsolutePath().normalize();
    }

    public Playbook load(String id) {
        return cache.computeIfAbsent(id, key -> {
            Path file = playbooksDir.resolve(key).resolve("scenario.yaml");
            if (!Files.exists(file)) {
                throw new IllegalArgumentException("Unknown scenario '" + key + "' (no " + file + ")");
            }
            try {
                return Json.yaml().readValue(file.toFile(), Playbook.class);
            } catch (IOException ex) {
                throw new UncheckedIOException("Cannot read playbook " + file, ex);
            }
        });
    }

    public List<String> scenarioIds() {
        try (var dirs = Files.list(playbooksDir)) {
            return dirs.filter(d -> Files.exists(d.resolve("scenario.yaml")))
                    .map(d -> d.getFileName().toString()).sorted().toList();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    public String content(String scenarioId, Playbook.Overlay overlay) {
        Path source = overlay.from() != null
                ? playbooksDir.resolve(scenarioId).resolve(overlay.from())
                : repoRoot.resolve(overlay.path());
        return read(source);
    }

    public String defectContent(String scenarioId, Playbook.Defect defect) {
        return read(playbooksDir.resolve(scenarioId).resolve(defect.from()));
    }

    /**
     * The final (defect-free) file set a scenario produces when all its features are implemented,
     * layered on its baselines. Used to seed brownfield sandboxes and by the consistency test.
     */
    public Map<String, String> finalFiles(String scenarioId) {
        Playbook playbook = load(scenarioId);
        Map<String, String> files = new LinkedHashMap<>();
        for (String base : playbook.baseline()) {
            files.putAll(finalFiles(base));
        }
        List<String> featureIds = playbook.features().stream().map(Playbook.Feature::id).toList();
        for (String featureId : featureIds) {
            Playbook.Feature feature = playbook.feature(featureId);
            for (Playbook.Overlay overlay : feature.main()) {
                files.put(overlay.path(), content(scenarioId, overlay));
            }
            for (Playbook.Overlay overlay : feature.tests()) {
                files.put(overlay.path(), content(scenarioId, overlay));
            }
        }
        return files;
    }

    public FaultInjector faults(String scenarioId) {
        return new FaultInjector(load(scenarioId).faults().stream()
                .map(f -> new FaultInjector.Fault(f.stage(), f.execution(), f.attempt(), f.kind(), f.message()))
                .toList());
    }

    public Path repoRoot() {
        return repoRoot;
    }

    private static String read(Path source) {
        try {
            return Files.readString(source, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException("Cannot read overlay source " + source, ex);
        }
    }
}
