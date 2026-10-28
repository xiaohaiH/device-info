package com.gathin.deviceinfo;

import java.util.Map;

/** Text-only report so the compatibility interpretation can be tested without a TV. */
final class DeviceReport {
    private DeviceReport() { }

    static String value(String text) {
        return text == null || text.trim().isEmpty() ? "未知" : text.trim();
    }

    static String memory(long bytes) {
        return bytes <= 0 ? "未知" : (bytes / (1024L * 1024L)) + " MiB";
    }

    static String compatibility(int sdk) {
        if (sdk <= 0) return "无法判断系统 API 等级，请保留完整设备信息。";
        if (sdk < 21) {
            return "本机 API " + sdk + " 低于 tvhome-zh-debug.apk 的最低要求 API 21（Android 5.0）。"
                    + "该桌面需要旧系统适配，不能仅修改版本号。";
        }
        return "本机 API " + sdk + " 达到 tvhome-zh-debug.apk 的最低要求 API 21（Android 5.0）。"
                + "这不保证一定能安装或运行，仍需检查安装包、签名和固件限制。";
    }

    static String render(Map<String, String> fields, int sdk) {
        StringBuilder text = new StringBuilder("安装判断依据：");
        text.append(sdk > 0 ? "API " + sdk : "未知").append("\n\n");
        for (Map.Entry<String, String> field : fields.entrySet()) {
            text.append(field.getKey()).append("：").append(value(field.getValue())).append("\n\n");
        }
        return text.append("桌面兼容性说明\n").append(compatibility(sdk)).toString();
    }
}
