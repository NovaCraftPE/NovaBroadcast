package uk.blazecraft.novabroadcast;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Offline structural diff for two account-owned MPSD activity dumps. */
final class ActivityDiff {
    record Change(String path, Object before, Object after) {}

    static List<Change> compare(String beforeJson, String afterJson) {
        Object before = Json.parse(beforeJson);
        Object after = Json.parse(afterJson);
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
        report.append("# changedPaths=").append(changes.size()).append("\n\n");
        for (Change change : changes) {
            report.append(change.path()).append('\n');
            report.append("  before: ").append(Json.stringify(change.before())).append('\n');
            report.append("  after:  ").append(Json.stringify(change.after())).append("\n\n");
        }
        Files.writeString(output, report.toString(), StandardCharsets.UTF_8);

        System.out.println("[ActivityDiff] Changed paths: " + changes.size());
        for (Change change : changes) System.out.println("[ActivityDiff] " + change.path());
        System.out.println("[ActivityDiff] Saved report to " + output.toAbsolutePath());
        System.out.println("[ActivityDiff] Treat changing title custom-property paths as dynamic until understood; do not publish captured values blindly.");
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
                if (!inA) out.add(new Change(child, null, b.get(key)));
                else if (!inB) out.add(new Change(child, a.get(key), null));
                else diff(child, a.get(key), b.get(key), out);
            }
            return;
        }
        if (before instanceof List<?> a && after instanceof List<?> b) {
            int max = Math.max(a.size(), b.size());
            for (int i = 0; i < max; i++) {
                String child = path + "[" + i + "]";
                if (i >= a.size()) out.add(new Change(child, null, b.get(i)));
                else if (i >= b.size()) out.add(new Change(child, a.get(i), null));
                else diff(child, a.get(i), b.get(i), out);
            }
            return;
        }
        out.add(new Change(path, before, after));
    }

    private ActivityDiff() {}
}
