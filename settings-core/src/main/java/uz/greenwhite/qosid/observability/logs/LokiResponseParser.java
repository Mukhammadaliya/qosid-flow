package uz.greenwhite.qosid.observability.logs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Parses Loki query_range / label-values JSON into normalized {@link LogEntry} rows. */
@RequiredArgsConstructor
public class LokiResponseParser {

    private final ObjectMapper objectMapper;

    /** LogEntry plus its raw nanosecond timestamp, kept only for exact newest-first ordering. */
    private record TimedEntry(long ns, LogEntry entry) {}

    public List<LogEntry> parse(String lokiJson) {
        List<TimedEntry> out = new ArrayList<>();
        try {
            JsonNode result = objectMapper.readTree(lokiJson).path("data").path("result");
            for (JsonNode stream : result) {
                Map<String, String> labels = new LinkedHashMap<>();
                // properties(), not the deprecated fields(): Jackson 2.19 (which Spring Boot 3.5
                // brings in) deprecated the Iterator-returning variant in favour of this Set.
                stream.path("stream").properties().forEach(e -> labels.put(e.getKey(), e.getValue().asText()));
                String level = labels.getOrDefault("level", "");
                for (JsonNode value : stream.path("values")) {
                    long ns = Long.parseLong(value.get(0).asText());
                    String line = value.get(1).asText();
                    out.add(new TimedEntry(ns, new LogEntry(nsToIso(ns), level, line, labels, tryParseJson(line))));
                }
            }
        } catch (Exception e) {
            return List.of();
        }
        // Sort by the raw nanosecond value, not the ISO string: Instant.toString() drops
        // trailing zero fraction digits, so string comparison mis-orders mixed precision.
        out.sort(Comparator.comparingLong(TimedEntry::ns).reversed()); // newest first
        return out.stream().map(TimedEntry::entry).toList();
    }

    public List<String> parseLabelValues(String lokiJson) {
        List<String> out = new ArrayList<>();
        try {
            objectMapper.readTree(lokiJson).path("data").forEach(n -> out.add(n.asText()));
        } catch (Exception e) {
            return List.of();
        }
        return out;
    }

    private String nsToIso(long ns) {
        return Instant.ofEpochSecond(ns / 1_000_000_000L, ns % 1_000_000_000L).toString();
    }

    private Object tryParseJson(String line) {
        String trimmed = line == null ? "" : line.trim();
        if (!trimmed.startsWith("{")) return null;
        try {
            return objectMapper.readValue(trimmed, Map.class);
        } catch (Exception e) {
            return null;
        }
    }
}
