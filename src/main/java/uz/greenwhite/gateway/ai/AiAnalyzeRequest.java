package uz.greenwhite.gateway.ai;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

public record AiAnalyzeRequest(String line, String level, Map<String, String> labels, JsonNode parsed) {}
