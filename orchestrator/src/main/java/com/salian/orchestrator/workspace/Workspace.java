package com.salian.orchestrator.workspace;

import com.fasterxml.jackson.core.type.TypeReference;
import com.salian.orchestrator.support.Json;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The run's sandbox. Agents can only change files through {@link #write}/{@link #delete}, which
 * journal the prior content per stage before touching anything (persisted immediately, so it
 * survives a crash). {@link #rollback(String)} restores exactly what a stage changed: this is the
 * compensation used for failed attempts, policy rework, replanning and crash recovery.
 *
 * <p>Guardrails enforced here (not just by policy scans): paths must stay inside the sandbox, and
 * two stages may not modify the same file while both of their changes are live (write-set conflict).
 */
public final class Workspace {

    private final Path root;
    private final Path journalDir;
    private final Map<String, Map<String, JournalEntry>> journals = new LinkedHashMap<>();

    public Workspace(Path root, Path journalDir) {
        this.root = root.toAbsolutePath().normalize();
        this.journalDir = journalDir.toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.root);
            Files.createDirectories(this.journalDir);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        loadJournals();
    }

    public Path root() {
        return root;
    }

    public synchronized void write(String stageId, String relativePath, String content) {
        Path target = resolve(relativePath);
        recordBeforeChange(stageId, relativePath, target);
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, content, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException("Cannot write " + relativePath, ex);
        }
    }

    public synchronized void delete(String stageId, String relativePath) {
        Path target = resolve(relativePath);
        recordBeforeChange(stageId, relativePath, target);
        try {
            Files.deleteIfExists(target);
        } catch (IOException ex) {
            throw new UncheckedIOException("Cannot delete " + relativePath, ex);
        }
    }

    /**
     * Seeds the sandbox with pre-existing (baseline) code before a run starts. Not journaled: the
     * baseline is the starting point, never something a stage can roll back.
     */
    public synchronized void seed(String relativePath, String content) {
        Path target = resolve(relativePath);
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, content, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException("Cannot seed " + relativePath, ex);
        }
    }

    public Optional<String> read(String relativePath) {
        Path target = resolve(relativePath);
        try {
            return Files.isRegularFile(target) ? Optional.of(Files.readString(target, StandardCharsets.UTF_8)) : Optional.empty();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    public boolean exists(String relativePath) {
        return Files.exists(resolve(relativePath));
    }

    /** All regular files under {@code relativeDir} (sandbox-relative, forward slashes), sorted. */
    public List<String> list(String relativeDir) {
        Path dir = resolve(relativeDir);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(Files::isRegularFile)
                    .map(p -> root.relativize(p).toString().replace('\\', '/'))
                    .filter(p -> !p.contains("/target/") && !p.startsWith("target/"))
                    .sorted()
                    .toList();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /** Files changed by a stage in its current (live) execution. */
    public synchronized List<String> changedBy(String stageId) {
        return List.copyOf(journals.getOrDefault(stageId, Map.of()).keySet());
    }

    /** Files changed by any live stage execution. */
    public synchronized Map<String, List<String>> allChanges() {
        Map<String, List<String>> result = new LinkedHashMap<>();
        journals.forEach((stage, entries) -> result.put(stage, List.copyOf(entries.keySet())));
        return result;
    }

    /** Journal entries of every live stage execution (path, whether it existed before the change). */
    public synchronized List<JournalEntry> allJournalEntries() {
        List<JournalEntry> entries = new ArrayList<>();
        journals.values().forEach(map -> entries.addAll(map.values()));
        return entries;
    }

    /** Restores every file the stage changed, newest first. Returns the restored paths. */
    public synchronized List<String> rollback(String stageId) {
        Map<String, JournalEntry> entries = journals.remove(stageId);
        if (entries == null || entries.isEmpty()) {
            deleteJournalFile(stageId);
            return List.of();
        }
        List<JournalEntry> ordered = new ArrayList<>(entries.values());
        Collections.reverse(ordered);
        List<String> restored = new ArrayList<>();
        for (JournalEntry entry : ordered) {
            Path target = resolve(entry.path());
            try {
                if (entry.existedBefore()) {
                    Files.createDirectories(target.getParent());
                    Files.copy(journalDir.resolve(entry.backupFile()), target, StandardCopyOption.REPLACE_EXISTING);
                } else {
                    Files.deleteIfExists(target);
                }
                restored.add(entry.path());
            } catch (IOException ex) {
                throw new UncheckedIOException("Rollback failed for " + entry.path(), ex);
            }
        }
        deleteJournalFile(stageId);
        return restored;
    }

    /** Makes a stage's changes permanent from the journal's point of view (no longer roll-back-able). */
    public synchronized void forget(String stageId) {
        journals.remove(stageId);
        deleteJournalFile(stageId);
    }

    private void recordBeforeChange(String stageId, String relativePath, Path target) {
        String key = normalize(relativePath);
        journals.forEach((otherStage, entries) -> {
            if (!otherStage.equals(stageId) && entries.containsKey(key)) {
                throw new WriteConflictException("Stage '" + stageId + "' tried to modify " + key
                        + " which has live changes from stage '" + otherStage + "'");
            }
        });
        Map<String, JournalEntry> entries = journals.computeIfAbsent(stageId, s -> new LinkedHashMap<>());
        if (entries.containsKey(key)) {
            return;
        }
        boolean existed = Files.isRegularFile(target);
        String backup = null;
        try {
            if (existed) {
                backup = stageId + "/" + entries.size() + ".bak";
                Path backupPath = journalDir.resolve(backup);
                Files.createDirectories(backupPath.getParent());
                Files.copy(target, backupPath, StandardCopyOption.REPLACE_EXISTING);
            }
            entries.put(key, new JournalEntry(key, existed, backup));
            Json.mapper().writeValue(journalDir.resolve(stageId + ".journal.json").toFile(), entries.values());
        } catch (IOException ex) {
            throw new UncheckedIOException("Cannot journal " + key, ex);
        }
    }

    private void loadJournals() {
        try (Stream<Path> files = Files.list(journalDir)) {
            for (Path file : files.filter(f -> f.getFileName().toString().endsWith(".journal.json")).toList()) {
                String stageId = file.getFileName().toString().replace(".journal.json", "");
                List<JournalEntry> entries = Json.mapper().readValue(file.toFile(), new TypeReference<>() { });
                Map<String, JournalEntry> map = new LinkedHashMap<>();
                entries.forEach(e -> map.put(e.path(), e));
                journals.put(stageId, map);
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private void deleteJournalFile(String stageId) {
        try {
            Files.deleteIfExists(journalDir.resolve(stageId + ".journal.json"));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private Path resolve(String relativePath) {
        Path resolved = root.resolve(normalize(relativePath)).normalize();
        if (!resolved.startsWith(root)) {
            throw new SandboxViolationException("Path escapes the sandbox: " + relativePath);
        }
        return resolved;
    }

    private static String normalize(String relativePath) {
        String path = relativePath.replace('\\', '/');
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        return path;
    }

    public record JournalEntry(String path, boolean existedBefore, String backupFile) {
    }

    public static class SandboxViolationException extends RuntimeException {
        public SandboxViolationException(String message) {
            super(message);
        }
    }

    public static class WriteConflictException extends RuntimeException {
        public WriteConflictException(String message) {
            super(message);
        }
    }
}
