package com.salian.orchestrator.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import com.salian.orchestrator.cli.OrchestratorFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Guarantees the committed url-shortener is exactly what the three scenarios produce when layered
 * (greenfield -> brownfield -> ambiguous with all features), so the demos and the product never drift.
 */
class PlaybookConsistencyTest {

    @Test
    void scenarioCompositionEqualsTheCommittedService() throws IOException {
        OrchestratorFactory.Paths paths = OrchestratorFactory.locate(Path.of("."));
        PlaybookRepository playbooks = new PlaybookRepository(paths.playbooks(), paths.repoRoot());
        Map<String, String> composed = playbooks.finalFiles("ambiguous");

        Path project = paths.repoRoot().resolve("url-shortener");
        Set<String> committed = new TreeSet<>();
        try (Stream<Path> files = Files.walk(project)) {
            files.filter(Files::isRegularFile)
                    .map(p -> "url-shortener/" + project.relativize(p).toString().replace('\\', '/'))
                    // Repository housekeeping (README, .gitignore, docs) is not product code produced by agents.
                    .filter(p -> !p.contains("/target/") && !p.endsWith("README.md") && !p.endsWith(".gitignore")
                            && !p.contains("/docs/"))
                    .forEach(committed::add);
        }

        assertThat(new TreeSet<>(composed.keySet())).isEqualTo(committed);
        for (String path : committed) {
            assertThat(composed.get(path)).as(path)
                    .isEqualTo(Files.readString(paths.repoRoot().resolve(path)));
        }
    }
}
