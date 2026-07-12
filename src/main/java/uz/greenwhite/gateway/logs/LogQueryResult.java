package uz.greenwhite.gateway.logs;

import java.util.List;

public record LogQueryResult(List<LogEntry> entries, String error) {}
