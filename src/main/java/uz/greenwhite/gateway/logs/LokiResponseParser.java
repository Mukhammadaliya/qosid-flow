package uz.greenwhite.gateway.logs;

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

    public List<LogEntry> parse(String lokiJson) {
        List<LogEntry> out = new ArrayList<>();
        try {
            JsonNode result = objectMapper.readTree(lokiJson).path("data").path("result");
            for (JsonNode stream : result) {
                Map<String, String> labels = new LinkedHashMap<>();
                stream.path("stream").fields().forEachRemaining(e -> labels.put(e.getKey(), e.getValue().asText()));
                String level = labels.getOrDefault("level", "");
                for (JsonNode value : stream.path("values")) {
                    String tsNs = value.get(0).asText();
                    String line = value.get(1).asText();
                    out.add(new LogEntry(nsToIso(tsNs), level, line, labels, tryParseJson(line)));
                }
            }
        } catch (Exception e) {
            return List.of();
        }
        out.sort(Comparator.comparing(LogEntry::timestamp).reversed()); // newest first
        return out;
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

    private String nsToIso(String tsNs) {
        long ns = Long.parseLong(tsNs);
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
