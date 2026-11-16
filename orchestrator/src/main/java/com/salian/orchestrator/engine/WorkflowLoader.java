package com.salian.orchestrator.engine;

import com.salian.orchestrator.model.WorkflowDefinition;
import com.salian.orchestrator.support.Json;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

public final class WorkflowLoader {

    private WorkflowLoader() {
    }

    public static WorkflowGraph load(Path yamlFile) {
        try {
            WorkflowDefinition definition = Json.yaml().readValue(yamlFile.toFile(), WorkflowDefinition.class);
            return new WorkflowGraph(definition);
        } catch (IOException ex) {
            throw new UncheckedIOException("Cannot load workflow " + yamlFile, ex);
        }
    }
}
