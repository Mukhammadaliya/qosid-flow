package uz.greenwhite.qosid.observability.stats;

import java.util.List;
import java.util.Map;

public record StatsResult(List<StatSeries> series, Map<String, Double> kpis, String error) {}
