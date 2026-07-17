package uz.greenwhite.gateway.admin.dto;

import java.util.List;

public record SettingsRegistryResponse(List<Item> settings) {
    public record Item(String key, String group, String label, String type,
                       String effectClass, String value, boolean secret) {}
}
