package uz.greenwhite.gateway.logs;

import java.util.Map;

public record LogEntry(String timestamp, String level, String line, Map<String, String> labels, Object parsed) {}
