package uz.greenwhite.qosid.observability.stats;

import java.util.List;

public record StatSeries(String name, List<StatPoint> points) {}
