package com.salian.orchestrator.engine;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class AgentRegistry {

    private final Map<String, Agent> agents = new LinkedHashMap<>();

    public AgentRegistry register(Agent agent) {
        agents.put(agent.name(), agent);
        return this;
    }

    public Optional<Agent> find(String name) {
        return Optional.ofNullable(agents.get(name));
    }

    public Collection<Agent> all() {
        return agents.values();
    }
}
