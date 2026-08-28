package uk.blazecraft.novabroadcast;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Offline importer for a dump produced by --dump-activities.
 * It never contacts Xbox services and never edits config.properties.
 */
final class ActivityImport {
    record Candidate(int index, String titleId, String scid, String templateName, String sessionName,
                     Map<String,Object> sessionCustom, Map<String,Object> memberCustom) {}

    static List<Candidate> parse(String json) {
        Object root = Json.parse(json);
        if (!(root instanceof Map<?,?> map) || !(map.get("results") instanceof List<?> results)) {
            throw new IllegalArgumentException("Activity dump does not contain a results array");
        }

        List<Candidate> candidates = new ArrayList<>();
        int sourceIndex = 0;
        for (Object item : results) {
            if (!(item instanceof Map<?,?> handle)) { sourceIndex++; continue; }
            Map<?,?> ref = asMap(handle.get("sessionRef"));
            if (ref == null) { sourceIndex++; continue; }

            String scid = value(ref.get("scid"));
            String template = value(ref.get("templateName"));
            String name = value(ref.get("name"));
            if (scid.isBlank() || template.isBlank() || name.isBlank()) { sourceIndex++; continue; }

            Map<?,?> session = asMap(handle.get("session"));
            Map<String,Object> sessionCustom = extractSessionCustom(session);
            Map<String,Object> memberCustom = extractSingleMemberCustom(session);

            candidates.add(new Candidate(
                    sourceIndex,
                    value(handle.get("titleId")),
                    scid,
                    template,
                    name,
                    sessionCustom,
                    memberCustom));
            sourceIndex++;
        }
        return candidates;
    }

    static List<Path> prepare(Path dump, Path outputRoot) throws Exception {
        if (!Files.isRegularFile(dump)) {
            throw new IllegalStateException("Activity dump not found: " + dump.toAbsolutePath());
        }
        List<Candidate> candidates = parse(Files.readString(dump, StandardCharsets.UTF_8));
        Files.createDirectories(outputRoot);

        List<Path> outputs = new ArrayList<>();
        for (Candidate candidate : candidates) {
            Path dir = outputRoot.resolve("candidate-" + candidate.index());
            Files.createDirectories(dir);

            Path sessionCustom = dir.resolve("session-custom.json");
            Files.writeString(sessionCustom, Json.stringify(candidate.sessionCustom()) + System.lineSeparator(), StandardCharsets.UTF_8);

            Path memberCustom = null;
            if (!candidate.memberCustom().isEmpty()) {
                memberCustom = dir.resolve("member-custom.json");
                Files.writeString(memberCustom, Json.stringify(candidate.memberCustom()) + System.lineSeparator(), StandardCharsets.UTF_8);
            }

            StringBuilder properties = new StringBuilder();
            properties.append("# Candidate extracted from data/mpsd-activities.json\n");
            if (!candidate.titleId().isBlank()) properties.append("# titleId=").append(candidate.titleId()).append('\n');
            properties.append("session.scid=").append(candidate.scid()).append('\n');
            properties.append("session.template=").append(candidate.templateName()).append('\n');
            properties.append("session.name=").append(candidate.sessionName()).append('\n');
            properties.append("session.customPropertiesFile=").append(normalize(sessionCustom)).append('\n');
            if (memberCustom != null) {
                properties.append("session.memberCustomPropertiesFile=").append(normalize(memberCustom)).append('\n');
            }
            properties.append("session.setActivity=false\n");
            properties.append("session.writeEnabled=false\n");

            Path suggestion = dir.resolve("session.properties");
            Files.writeString(suggestion, properties.toString(), StandardCharsets.UTF_8);
            outputs.add(dir);
        }
        return outputs;
    }

    private static Map<String,Object> extractSessionCustom(Map<?,?> session) {
        if (session == null) return new LinkedHashMap<>();
        Map<?,?> properties = asMap(session.get("properties"));
        Map<?,?> custom = properties == null ? null : asMap(properties.get("custom"));
        return copyStringMap(custom);
    }

    /**
     * Only auto-select a member custom object when it is unambiguous. If the
     * included session has several members with different custom blobs, leave
     * the member file absent rather than guessing which one belongs to the host.
     */
    private static Map<String,Object> extractSingleMemberCustom(Map<?,?> session) {
        if (session == null) return new LinkedHashMap<>();
        Map<?,?> members = asMap(session.get("members"));
        if (members == null || members.isEmpty()) return new LinkedHashMap<>();

        Map<String,Object> unique = null;
        for (Object memberObj : members.values()) {
            Map<?,?> member = asMap(memberObj);
            if (member == null) continue;
            Map<?,?> properties = asMap(member.get("properties"));
            Map<?,?> custom = properties == null ? null : asMap(properties.get("custom"));
            Map<String,Object> copied = copyStringMap(custom);
            if (copied.isEmpty()) continue;
            if (unique == null) unique = copied;
            else if (!unique.equals(copied)) return new LinkedHashMap<>();
        }
        return unique == null ? new LinkedHashMap<>() : unique;
    }

    private static Map<String,Object> copyStringMap(Map<?,?> source) {
        Map<String,Object> out = new LinkedHashMap<>();
        if (source == null) return out;
        for (Map.Entry<?,?> e : source.entrySet()) {
            if (e.getKey() instanceof String key) out.put(key, e.getValue());
        }
        return out;
    }

    private static Map<?,?> asMap(Object value) {
        return value instanceof Map<?,?> map ? map : null;
    }

    private static String value(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String normalize(Path path) {
        return path.toString().replace('\\', '/');
    }

    private ActivityImport() {}
}
