package uz.greenwhite.qosid.observability.stats;

import java.util.LinkedHashMap;

/** A named dashboard query: a set of (seriesName -> fixed PromQL) run as instant or range. */
public record StatPreset(Kind kind, LinkedHashMap<String, String> queries) {
    public enum Kind { INSTANT, RANGE }
}
