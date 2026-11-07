package org.openhab.io.yandexalice.internal;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONObject;

/** Computes the local Items represented by a successful remote Items response. */
public final class RemoteMirrorPlanner {
    public record Spec(String sourceName, String localName, String type, String label, String category,
            Set<String> tags, List<String> groups, String state, String groupType, String functionName,
            List<String> functionParams) {
    }

    private RemoteMirrorPlanner() {
    }

    public static List<Spec> plan(JSONArray response, String tag, String prefix) {
        Map<String, JSONObject> source = new LinkedHashMap<>();
        Map<String, List<String>> members = new HashMap<>();
        for (int i = 0; i < response.length(); i++) {
            JSONObject item = response.optJSONObject(i);
            if (item == null)
                continue;
            String name = item.optString("name", "");
            String type = item.optString("type", "");
            if (name.isBlank() || type.isBlank())
                continue;
            source.put(name, item);
            for (String group : strings(item.optJSONArray("groupNames"))) {
                members.computeIfAbsent(group, ignored -> new ArrayList<>()).add(name);
            }
        }

        Set<String> selected = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        for (Map.Entry<String, JSONObject> entry : source.entrySet()) {
            if (strings(entry.getValue().optJSONArray("tags")).contains(tag)) {
                selected.add(entry.getKey());
                queue.add(entry.getKey());
            }
        }
        while (!queue.isEmpty()) {
            String name = queue.removeFirst();
            if (!"Group".equals(source.get(name).optString("type")))
                continue;
            for (String member : members.getOrDefault(name, List.of())) {
                if (selected.add(member))
                    queue.addLast(member);
            }
        }

        List<Spec> result = new ArrayList<>();
        Set<String> completed = new HashSet<>();
        Set<String> visiting = new HashSet<>();
        for (String name : selected) {
            visit(name, source, selected, prefix, result, completed, visiting);
        }
        return List.copyOf(result);
    }

    private static void visit(String name, Map<String, JSONObject> source, Set<String> selected, String prefix,
            List<Spec> result, Set<String> completed, Set<String> visiting) {
        if (completed.contains(name))
            return;
        if (!visiting.add(name))
            throw new IllegalArgumentException("Cyclic remote Group hierarchy: " + name);
        JSONObject item = source.get(name);
        List<String> groups = new ArrayList<>();
        for (String group : strings(item.optJSONArray("groupNames"))) {
            if (selected.contains(group) && "Group".equals(source.get(group).optString("type"))) {
                visit(group, source, selected, prefix, result, completed, visiting);
                groups.add(prefix + group);
            }
        }
        Collections.sort(groups);
        String label = item.optString("label", "");
        if (label.isBlank())
            label = name;
        JSONObject function = item.optJSONObject("function");
        result.add(new Spec(name, prefix + name, item.getString("type"), label, item.optString("category", ""),
                Set.copyOf(strings(item.optJSONArray("tags"))), List.copyOf(groups), item.optString("state", ""),
                item.optString("groupType", ""), function == null ? "" : function.optString("name", ""),
                function == null ? List.of() : List.copyOf(strings(function.optJSONArray("params")))));
        visiting.remove(name);
        completed.add(name);
    }

    private static List<String> strings(JSONArray array) {
        if (array == null)
            return List.of();
        List<String> values = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            String value = array.optString(i, "");
            if (!value.isBlank())
                values.add(value);
        }
        return values;
    }
}
