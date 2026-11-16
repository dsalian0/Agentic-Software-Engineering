package com.salian.orchestrator.agents;

import com.salian.orchestrator.engine.Agent;
import com.salian.orchestrator.engine.ExecutionMode;
import com.salian.orchestrator.engine.StageContext;
import com.salian.orchestrator.engine.StageResult;
import com.salian.orchestrator.support.Json;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Packages the approved change as a release candidate manifest (file list + content hashes).
 * Deliberately does NOT write to the repository, commit, push or merge: promotion of a release
 * candidate into the real codebase is a human action outside agent autonomy.
 */
public final class PublisherAgent implements Agent {

    @Override
    public String name() {
        return "publisher";
    }

    @Override
    public ExecutionMode mode() {
        return ExecutionMode.REAL;
    }

    @Override
    public StageResult execute(StageContext ctx) {
        List<Map<String, String>> files = new ArrayList<>();
        StringBuilder all = new StringBuilder();
        for (String path : ctx.workspace().list(Scenarios.PROJECT)) {
            String hash = Json.sha256(ctx.workspace().read(path).orElse(""));
            files.add(Map.of("path", path, "sha256", hash));
            all.append(path).append(':').append(hash).append('\n');
        }
        Map<String, Object> bundle = new LinkedHashMap<>();
        bundle.put("summary", "Release candidate: " + files.size() + " files, manifest "
                + Json.sha256(all.toString()).substring(0, 12));
        bundle.put("manifestHash", Json.sha256(all.toString()));
        bundle.put("location", ctx.workspace().root().resolve(Scenarios.PROJECT).toString());
        bundle.put("files", files);
        bundle.put("promotion", "Not performed by agents. A human reviews the release summary and promotes "
                + "the candidate (copy/commit/PR) under normal change management.");
        ctx.output("release-bundle", bundle);
        return StageResult.ok((String) bundle.get("summary"));
    }
}
