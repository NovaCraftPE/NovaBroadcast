package uk.blazecraft.novabroadcast;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Offline structural diff for two account-owned MPSD activity dumps. */
final class ActivityDiff {
    enum Kind { CUSTOM, SESSION_REF, SYSTEM, OTHER }
    record Change(String path, Object before, Object after, Kind kind) {}

    static List<Change> compare(String beforeJson, String afterJson) {
        Object before = normalizeActivityResults(Json.parse(beforeJson));
        Object after = normalizeActivityResults(Json.parse(afterJson));
        List<Change> changes = new ArrayList<>();
        diff("$", before, after, changes);
        return changes;
    }

    static void run(Path before, Path after, Path output) throws Exception {
        if (!Files.isRegularFile(before)) throw new IllegalStateException("First activity dump not found: " + before.toAbsolutePath());
        if (!Files.isRegularFile(after)) throw new IllegalStateException("Second activity dump not found: " + after.toAbsolutePath());

        List<Change> changes = compare(
                Files.readString(before, StandardCharsets.UTF_8),
                Files.readString(after, StandardCharsets.UTF_8));

        Path parent = output.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        StringBuilder report = new StringBuilder();
        report.append("# NovaBroadcast MPSD activity diff\n");
        report.append("# before=").append(before).append('\n');
        report.append("# after=").append(after).append('\n');
        report.append("# changedPaths=").append(changes.size()).append("\n");
        report.append("# Results are matched by titleId + SCID + template when possible, so array reordering is ignored.\n\n");
        for (Change change : changes) {
            report.append('[').append(change.kind()).append("] ").append(change.path()).append('\n');
            report.append("  before: ").append(Json.stringify(change.before())).append('\n');
            report.append("  after:  ").append(Json.stringify(change.after())).append("\n\n");
        }
        Files.writeString(output, report.toString(), StandardCharsets.UTF_8);

        Map<Kind,Long> counts = new EnumMap<>(Kind.class);
        for (Kind kind : Kind.values()) counts.put(kind, changes.stream().filter(c -> c.kind() == kind).count());
        System.out.println("[ActivityDiff] Changed paths: " + changes.size() +
                " custom=" + counts.get(Kind.CUSTOM) +
                " sessionRef=" + counts.get(Kind.SESSION_REF) +
                " system=" + counts.get(Kind.SYSTEM) +
                " other=" + counts.get(Kind.OTHER));
        for (Change change : changes) {
            System.out.println("[ActivityDiff] [" + change.kind() + "] " + change.path());
        }
        System.out.println("[ActivityDiff] Saved report to " + output.toAbsolutePath());
        System.out.println("[ActivityDiff] A changed custom-property path is evidence that the value is session-dependent in these captures; do not publish captured values blindly.");
    }

    /**
     * MPSD handle queries return a results array whose order is not an identity contract.
     * Re-key complete activity entries by titleId + SCID + template before diffing so a
     * reorder does not masquerade as title/session changes. Session name is deliberately
     * excluded because it is one of the values we want to observe changing.
     */
    private static Object normalizeActivityResults(Object root) {
        if (!(root instanceof Map<?,?> source)) return root;
        Map<String,Object> normalized = copyMap(source);
        Object resultsObj = source.get("results");
        if (!(resultsObj instanceof List<?> results)) return normalized;

        Map<String,Object> keyed = new TreeMap<>();
        int fallback = 0;
        for (Object item : results) {
            if (!(item instanceof Map<?,?> handle)) {
                keyed.put("fallback-" + fallback++, item);
                continue;
            }
            Map<?,?> ref = handle.get("sessionRef") instanceof Map<?,?> map ? map : null;
            String titleId = value(handle.get("titleId"));
            String scid = ref == null ? "" : value(ref.get("scid"));
            String template = ref == null ? "" : value(ref.get("templateName"));
            String base = (!titleId.isBlank() || !scid.isBlank() || !template.isBlank())
                    ? "title=" + titleId + "|scid=" + scid + "|template=" + template
                    : "fallback-" + fallback++;
            String key = base;
            int duplicate = 2;
            while (keyed.containsKey(key)) key = base + "#" + duplicate++;
            keyed.put(key, item);
        }
        normalized.put("results", keyed);
        return normalized;
    }

    private static Map<String,Object> copyMap(Map<?,?> source) {
        Map<String,Object> out = new LinkedHashMap<>();
        for (Map.Entry<?,?> entry : source.entrySet()) {
            if (entry.getKey() instanceof String key) out.put(key, entry.getValue());
        }
        return out;
    }

    private static void diff(String path, Object before, Object after, List<Change> out) {
        if (Objects.equals(before, after)) return;
        if (before instanceof Map<?,?> a && after instanceof Map<?,?> b) {
            Set<String> keys = new TreeSet<>();
            for (Object key : a.keySet()) if (key instanceof String s) keys.add(s);
            for (Object key : b.keySet()) if (key instanceof String s) keys.add(s);
            for (String key : keys) {
                boolean inA = a.containsKey(key), inB = b.containsKey(key);
                String child = path + "." + key;
                if (!inA) add(out, child, null, b.get(key));
                else if (!inB) add(out, child, a.get(key), null);
                else diff(child, a.get(key), b.get(key), out);
            }
            return;
        }
        if (before instanceof List<?> a && after instanceof List<?> b) {
            int max = Math.max(a.size(), b.size());
            for (int i = 0; i < max; i++) {
                String child = path + "[" + i + "]";
                if (i >= a.size()) add(out, child, null, b.get(i));
                else if (i >= b.size()) add(out, child, a.get(i), null);
                else diff(child, a.get(i), b.get(i), out);
            }
            return;
        }
        add(out, path, before, after);
    }

    private static void add(List<Change> out, String path, Object before, Object after) {
        out.add(new Change(path, before, after, classify(path)));
    }

    private static Kind classify(String path) {
        if (path.contains(".properties.custom")) return Kind.CUSTOM;
        if (path.contains(".sessionRef.")) return Kind.SESSION_REF;
        if (path.contains(".properties.system") || path.contains(".constants.system") || path.contains(".info.")) return Kind.SYSTEM;
        return Kind.OTHER;
    }

    private static String value(Object value) { return value == null ? "" : String.valueOf(value); }
    private ActivityDiff() {}
}
