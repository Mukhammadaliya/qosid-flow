package uz.greenwhite.gateway.stats;

import java.util.List;

public record StatSeries(String name, List<StatPoint> points) {}
