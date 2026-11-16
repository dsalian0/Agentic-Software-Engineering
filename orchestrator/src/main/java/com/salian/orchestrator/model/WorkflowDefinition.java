package com.salian.orchestrator.model;

import java.util.List;

public record WorkflowDefinition(String id, String version, String description, Budget budget,
                                 List<StageDefinition> stages) {

    public WorkflowDefinition {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("workflow id is required");
        }
        budget = budget == null ? Budget.DEFAULT : budget;
        stages = stages == null ? List.of() : List.copyOf(stages);
    }
}
