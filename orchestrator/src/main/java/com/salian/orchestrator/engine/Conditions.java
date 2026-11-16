package com.salian.orchestrator.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.salian.orchestrator.model.RunState;

/**
 * Evaluates a stage's {@code when} guard. Grammar (deliberately tiny and side-effect free):
 * {@code [!]artifactKey.field.path} or {@code [!]param:name}. A missing value is false.
 */
public final class Conditions {

    private Conditions() {
    }

    public static boolean evaluate(String expression, RunState state) {
        if (expression == null || expression.isBlank()) {
            return true;
        }
        String expr = expression.trim();
        boolean negate = expr.startsWith("!");
        if (negate) {
            expr = expr.substring(1).trim();
        }
        boolean value;
        if (expr.startsWith("param:")) {
            String param = state.getParameters().get(expr.substring("param:".length()));
            value = param != null && !param.isBlank() && !"false".equalsIgnoreCase(param);
        } else {
            int dot = expr.indexOf('.');
            if (dot < 0) {
                value = state.latest(expr).isPresent();
            } else {
                String key = expr.substring(0, dot);
                String path = "/" + expr.substring(dot + 1).replace('.', '/');
                value = state.latest(key).map(a -> truthy(a.getContent().at(path))).orElse(false);
            }
        }
        return negate != value;
    }

    private static boolean truthy(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return false;
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isNumber()) {
            return node.asDouble() != 0;
        }
        if (node.isTextual()) {
            return !node.asText().isBlank();
        }
        return node.size() > 0;
    }
}
