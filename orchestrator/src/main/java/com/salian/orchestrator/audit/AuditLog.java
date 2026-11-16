package com.salian.orchestrator.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.salian.orchestrator.support.Json;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Append-only, hash-chained JSONL audit trail for one run. Thread-safe; every append is flushed to
 * disk before returning so the trail survives a crash.
 */
public final class AuditLog {

    public static final String GENESIS = "0".repeat(64);

    private final Path file;
    private final String runId;
    private final Clock clock;
    private long seq;
    private String lastHash = GENESIS;
    private long knownSize = -1;

    public AuditLog(Path file, String runId, Clock clock) {
        this.file = file;
        this.runId = runId;
        this.clock = clock;
    }

    /**
     * Appends under an exclusive file lock. If another writer (e.g. the {@code stop} command in a
     * separate process) appended since our last write, the chain tail is re-read first so the chain
     * stays intact across writers.
     */
    public synchronized AuditEvent append(String type, String stageId, Integer attempt, String actor, Object data) {
        JsonNode payload = data == null ? Json.mapper().createObjectNode() : Json.tree(data);
        try {
            Files.createDirectories(file.getParent());
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.READ);
                 FileLock ignored = channel.lock()) {
                if (channel.size() != knownSize) {
                    // Read through the locked channel: on Windows a second handle cannot read a locked region.
                    List<AuditEvent> existing = readAll(channel);
                    seq = existing.isEmpty() ? 0 : existing.get(existing.size() - 1).seq();
                    lastHash = existing.isEmpty() ? GENESIS : existing.get(existing.size() - 1).hash();
                }
                AuditEvent unsigned = new AuditEvent(seq + 1, UUID.randomUUID().toString(), runId, type, stageId,
                        attempt, actor, clock.instant(), payload, lastHash, null);
                AuditEvent signed = unsigned.withHash(hashOf(unsigned));
                byte[] line = (Json.mapper().writeValueAsString(signed) + "\n").getBytes(StandardCharsets.UTF_8);
                channel.position(channel.size());
                channel.write(ByteBuffer.wrap(line));
                channel.force(true);
                knownSize = channel.size();
                seq = signed.seq();
                lastHash = signed.hash();
                return signed;
            }
        } catch (IOException ex) {
            throw new UncheckedIOException("Cannot append audit event", ex);
        }
    }

    public List<AuditEvent> events() {
        return Files.exists(file) ? read(file) : List.of();
    }

    public Path file() {
        return file;
    }

    /** Recomputes the chain; returns the problems found (empty = intact). */
    public VerificationResult verify() {
        return verify(events());
    }

    public static VerificationResult verify(List<AuditEvent> events) {
        List<String> problems = new ArrayList<>();
        String expectedPrev = GENESIS;
        long expectedSeq = 1;
        for (AuditEvent event : events) {
            if (event.seq() != expectedSeq) {
                problems.add("seq " + event.seq() + ": expected seq " + expectedSeq);
            }
            if (!expectedPrev.equals(event.prevHash())) {
                problems.add("seq " + event.seq() + ": prevHash does not match previous event");
            }
            if (!hashOf(event.withoutHash()).equals(event.hash())) {
                problems.add("seq " + event.seq() + ": content hash mismatch (event was modified)");
            }
            expectedPrev = event.hash();
            expectedSeq = event.seq() + 1;
        }
        return new VerificationResult(problems.isEmpty(), events.size(), problems);
    }

    public static List<AuditEvent> read(Path file) {
        try {
            List<AuditEvent> events = new ArrayList<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    events.add(Json.mapper().readValue(line, AuditEvent.class));
                }
            }
            return events;
        } catch (IOException ex) {
            throw new UncheckedIOException("Cannot read audit log " + file, ex);
        }
    }

    private static List<AuditEvent> readAll(FileChannel channel) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate((int) channel.size());
        channel.position(0);
        while (buffer.hasRemaining() && channel.read(buffer) > 0) {
            // keep reading
        }
        List<AuditEvent> events = new ArrayList<>();
        for (String line : new String(buffer.array(), StandardCharsets.UTF_8).split("\n")) {
            if (!line.isBlank()) {
                events.add(Json.mapper().readValue(line, AuditEvent.class));
            }
        }
        return events;
    }

    private static String hashOf(AuditEvent unsigned) {
        return Json.sha256(unsigned.prevHash() + Json.canonical(unsigned.withoutHash()));
    }

    public record VerificationResult(boolean intact, int events, List<String> problems) {
    }
}
