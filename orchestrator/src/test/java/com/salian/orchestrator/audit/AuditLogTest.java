package com.salian.orchestrator.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AuditLogTest {

    @TempDir
    Path dir;

    @Test
    void chainVerifiesAndSurvivesReopen() {
        Path file = dir.resolve("audit.jsonl");
        AuditLog log = new AuditLog(file, "r1", Clock.systemUTC());
        log.append("A", "s1", 1, "agent:x", Map.of("k", "v"));
        log.append("B", null, null, "system:orchestrator", null);

        AuditLog reopened = new AuditLog(file, "r1", Clock.systemUTC());
        reopened.append("C", "s2", 2, "human:lead", Map.of("n", 3));

        AuditLog.VerificationResult result = reopened.verify();
        assertThat(result.intact()).isTrue();
        assertThat(result.events()).isEqualTo(3);
        assertThat(reopened.events().get(2).seq()).isEqualTo(3);
    }

    @Test
    void detectsTamperingAndDeletion() throws Exception {
        Path file = dir.resolve("audit.jsonl");
        AuditLog log = new AuditLog(file, "r1", Clock.systemUTC());
        log.append("APPROVAL_REJECTED", "approve", null, "human:lead", Map.of("comment", "no"));
        log.append("STAGE_SUCCEEDED", "build", 1, "system:orchestrator", Map.of());
        log.append("RUN_COMPLETED", null, null, "system:orchestrator", Map.of());

        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        Files.write(file, List.of(lines.get(0).replace("APPROVAL_REJECTED", "APPROVAL_GRANTED"), lines.get(1), lines.get(2)));
        assertThat(AuditLog.verify(AuditLog.read(file)).problems()).anyMatch(p -> p.contains("content hash mismatch"));

        Files.write(file, List.of(lines.get(0), lines.get(2)));
        assertThat(AuditLog.verify(AuditLog.read(file)).intact()).isFalse();
    }
}
