package com.salian.orchestrator.engine;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class GateRegistry {

    private final Map<String, Gate> gates = new LinkedHashMap<>();

    public GateRegistry register(Gate gate) {
        gates.put(gate.name(), gate);
        return this;
    }

    public Optional<Gate> find(String name) {
        return Optional.ofNullable(gates.get(name));
    }
}
