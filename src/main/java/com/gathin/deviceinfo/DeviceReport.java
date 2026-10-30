package com.gathin.deviceinfo;

import java.util.Map;

/** Text-only device information formatting, testable without Android. */
final class DeviceReport {
    private DeviceReport() { }

    static String value(String text) {
        return text == null || text.trim().isEmpty() ? "未知" : text.trim();
    }

    static String memory(long bytes) {
        return bytes <= 0 ? "未知" : (bytes / (1024L * 1024L)) + " MiB";
    }

    static String render(Map<String, String> fields, int sdk) {
        StringBuilder text = new StringBuilder("系统 API 等级：");
        text.append(sdk > 0 ? String.valueOf(sdk) : "未知").append("\n\n");
        for (Map.Entry<String, String> field : fields.entrySet()) {
            text.append(field.getKey()).append("：").append(value(field.getValue())).append("\n\n");
        }
        return text.toString();
    }
}
