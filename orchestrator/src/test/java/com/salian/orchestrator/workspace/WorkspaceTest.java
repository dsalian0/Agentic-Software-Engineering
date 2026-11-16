package com.salian.orchestrator.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceTest {

    @TempDir
    Path dir;

    @Test
    void rollbackRestoresModifiedAndRemovesCreatedFiles() throws Exception {
        Workspace ws = new Workspace(dir.resolve("ws"), dir.resolve("journal"));
        Files.createDirectories(ws.root().resolve("src"));
        Files.writeString(ws.root().resolve("src/App.java"), "v1");

        ws.write("impl", "src/App.java", "v2");
        ws.write("impl", "src/App.java", "v3");
        ws.write("impl", "src/New.java", "new");
        ws.delete("impl", "src/App.java");

        assertThat(ws.changedBy("impl")).containsExactly("src/App.java", "src/New.java");
        assertThat(ws.rollback("impl")).containsExactlyInAnyOrder("src/App.java", "src/New.java");
        assertThat(Files.readString(ws.root().resolve("src/App.java"))).isEqualTo("v1");
        assertThat(Files.exists(ws.root().resolve("src/New.java"))).isFalse();
    }

    @Test
    void journalSurvivesProcessRestart() throws Exception {
        Workspace ws = new Workspace(dir.resolve("ws"), dir.resolve("journal"));
        ws.write("impl", "a.txt", "agent output");

        Workspace reopened = new Workspace(dir.resolve("ws"), dir.resolve("journal"));
        assertThat(reopened.rollback("impl")).containsExactly("a.txt");
        assertThat(Files.exists(reopened.root().resolve("a.txt"))).isFalse();
    }

    @Test
    void blocksSandboxEscapeAndConcurrentOwnership() {
        Workspace ws = new Workspace(dir.resolve("ws"), dir.resolve("journal"));

        assertThatThrownBy(() -> ws.write("impl", "../outside.txt", "x"))
                .isInstanceOf(Workspace.SandboxViolationException.class);
        ws.write("impl", "shared.txt", "a");
        assertThatThrownBy(() -> ws.write("tests", "shared.txt", "b"))
                .isInstanceOf(Workspace.WriteConflictException.class);
        ws.rollback("impl");
        ws.write("tests", "shared.txt", "b");
    }
}
