package uz.greenwhite.qosid.observability.logs;

import java.util.List;

public record LogQueryResult(List<LogEntry> entries, String error) {}
