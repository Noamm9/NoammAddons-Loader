package com.github.noamm9.loader;

import net.fabricmc.loader.impl.lib.gson.JsonReader;

import java.io.IOException;
import java.io.StringReader;
import java.util.*;

record ActionBuild(String sha, String artifactId, String artifactName, String digest) {
    @SuppressWarnings("unchecked")
    static ActionBuild parseLatest(String json, String assetSuffix) throws IOException {
        var root = (Map<String, Object>) read(new JsonReader(new StringReader(json)));
        if (root == null) return null;
        var runs = (List<Object>) root.get("workflow_runs");
        if (runs == null || runs.isEmpty()) return null;

        var run = (Map<String, Object>) runs.getFirst();
        var artifacts = (List<Object>) run.getOrDefault("artifacts", List.of());

        for (Object entry : artifacts) {
            var artifact = (Map<String, Object>) entry;
            var name = (String) artifact.get("name");
            if (name == null || ! name.endsWith(assetSuffix)) continue;

            return new ActionBuild(
                (String) run.get("head_sha"),
                (String) artifact.get("id"),
                name,
                (String) artifact.get("digest")
            );
        }

        return null;
    }

    static Object read(JsonReader reader) throws IOException {
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                Map<String, Object> map = new LinkedHashMap<>();
                reader.beginObject();
                while (reader.hasNext()) map.put(reader.nextName(), read(reader));
                reader.endObject();
                yield map;
            }
            case BEGIN_ARRAY -> {
                List<Object> list = new ArrayList<>();
                reader.beginArray();
                while (reader.hasNext()) list.add(read(reader));
                reader.endArray();
                yield list;
            }
            default -> reader.nextString();
        };
    }
}