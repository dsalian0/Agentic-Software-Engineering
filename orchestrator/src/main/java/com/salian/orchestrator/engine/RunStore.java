package com.salian.orchestrator.engine;

import com.salian.orchestrator.model.RunState;
import com.salian.orchestrator.support.Json;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.stream.Stream;

/** File-based run persistence: runs/&lt;runId&gt;/state.json, written atomically (temp file + move). */
public final class RunStore {

    private final Path runsDir;

    public RunStore(Path runsDir) {
        this.runsDir = runsDir.toAbsolutePath().normalize();
    }

    public Path runsDir() {
        return runsDir;
    }

    public Path runDir(String runId) {
        if (!runId.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("Invalid run id: " + runId);
        }
        return runsDir.resolve(runId);
    }

    public boolean exists(String runId) {
        return Files.exists(runDir(runId).resolve("state.json"));
    }

    public synchronized void save(RunState state) {
        Path dir = runDir(state.getRunId());
        try {
            Files.createDirectories(dir);
            Path tmp = dir.resolve("state.json.tmp");
            Files.writeString(tmp, Json.pretty(state));
            try {
                Files.move(tmp, dir.resolve("state.json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(tmp, dir.resolve("state.json"), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            throw new UncheckedIOException("Cannot persist run state " + state.getRunId(), ex);
        }
    }

    public RunState load(String runId) {
        Path file = runDir(runId).resolve("state.json");
        if (!Files.exists(file)) {
            throw new IllegalArgumentException("No such run: " + runId);
        }
        try {
            return Json.mapper().readValue(file.toFile(), RunState.class);
        } catch (IOException ex) {
            throw new UncheckedIOException("Cannot read run state " + runId, ex);
        }
    }

    public List<String> listRunIds() {
        if (!Files.isDirectory(runsDir)) {
            return List.of();
        }
        try (Stream<Path> dirs = Files.list(runsDir)) {
            return dirs.filter(d -> Files.exists(d.resolve("state.json")))
                    .map(d -> d.getFileName().toString())
                    .sorted()
                    .toList();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
